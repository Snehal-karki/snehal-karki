package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.AppUsageStatRecord
import com.example.data.model.ScreenStreamSession
import com.example.data.model.UserFirebaseProfile
import com.example.data.remote.OperationType
import com.example.data.remote.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

data class AppUsageEntry(
    val packageName: String,
    val appName: String,
    val category: String,
    val foregroundTimeMs: Long,
    val totalTimeMinutes: Int,
    val launchCount: Int,
    val isBlocked: Boolean = false
)

class FirebaseLiveRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        Firebase.auth
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun sanitizeFirestoreId(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[^a-zA-Z0-9_\\-.]"), "_")
        return if (cleaned.isNotBlank() && cleaned.length <= 128) cleaned else sha256Hex(raw).take(32)
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun normalizedMembers(uid: String, extraMembers: List<String>): List<String> {
        return (listOf(uid) + extraMembers.filter { it.isNotBlank() }).distinct().take(10)
    }

    // -----------------------------------------------------------------
    // User Profile Sync (/users/{uid})
    // -----------------------------------------------------------------

    suspend fun upsertUserProfile(
        displayName: String,
        role: String,
        familyId: String,
        supabaseUserId: String
    ): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Must sign in with Google first."))
        val normalizedRole = when (role.lowercase()) {
            "parent" -> "parent"
            "child" -> "child"
            else -> "unassigned"
        }
        val docRef = db.collection("users").document(uid)
        return try {
            val snap = docRef.get().await()
            if (!snap.exists()) {
                val payload = mapOf(
                    "userId" to uid,
                    "displayName" to displayName.take(100),
                    "role" to normalizedRole,
                    "familyId" to familyId.take(128),
                    "supabaseUserId" to supabaseUserId.take(128),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            } else {
                val updates = mapOf(
                    "displayName" to displayName.take(100),
                    "role" to normalizedRole,
                    "familyId" to familyId.take(128),
                    "supabaseUserId" to supabaseUserId.take(128),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun getUserProfile(): Result<UserFirebaseProfile?> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("User not authenticated."))
        val docRef = db.collection("users").document(uid)
        return try {
            val snap = docRef.get().await()
            val profile = snap.toObject(UserFirebaseProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            Result.success(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    // -----------------------------------------------------------------
    // Real-time Child Screen Mirror (/screen_streams/{childId})
    // -----------------------------------------------------------------

    fun observeScreenStream(childId: String): Flow<ScreenStreamSession?> = flow {
        val uid = requireUserId()
        val cleanChildId = sanitizeFirestoreId(childId)
        val path = "screen_streams"
        emitAll(
            db.collection(path)
                .whereEqualTo("childId", cleanChildId)
                .whereArrayContains("memberUids", uid)
                .limit(1)
                .snapshots()
                .map { snapshot ->
                    snapshot.documents.firstOrNull()?.toObject(
                        ScreenStreamSession::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun requestOrStopScreenWatch(
        childId: String,
        childName: String,
        memberUids: List<String>,
        watchRequested: Boolean
    ): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Must sign in with Google first."))
        val cleanChildId = sanitizeFirestoreId(childId)
        val docRef = db.collection("screen_streams").document(cleanChildId)
        return try {
            val existingQuery = db.collection("screen_streams")
                .whereEqualTo("childId", cleanChildId)
                .whereArrayContains("memberUids", uid)
                .limit(1)
                .get()
                .await()
            val existingDoc = existingQuery.documents.firstOrNull()
            val members = normalizedMembers(uid, memberUids)

            if (existingDoc == null) {
                val payload = mapOf(
                    "childId" to cleanChildId,
                    "ownerUid" to uid,
                    "memberUids" to members,
                    "childName" to childName.ifBlank { "Child" }.take(100),
                    "foregroundApp" to "",
                    "frameBase64" to "",
                    "width" to 0,
                    "height" to 0,
                    "isStreaming" to false,
                    "watchRequested" to watchRequested,
                    "requestedByUid" to if (watchRequested) uid else "",
                    "fps" to 0.0,
                    "capturedAtMs" to System.currentTimeMillis(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            } else {
                val existingMembers = (existingDoc.get("memberUids") as? List<*>)
                    ?.filterIsInstance<String>()
                    .orEmpty()
                val mergedMembers = (existingMembers + members).distinct().take(10)
                val updates = mapOf(
                    "watchRequested" to watchRequested,
                    "requestedByUid" to if (watchRequested) uid else "",
                    "memberUids" to mergedMembers,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun pushScreenFrame(
        childId: String,
        childName: String,
        foregroundApp: String,
        frameBase64: String,
        width: Int,
        height: Int,
        isStreaming: Boolean,
        fps: Double,
        memberUids: List<String>
    ): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Must sign in with Google first."))
        val cleanChildId = sanitizeFirestoreId(childId)
        val docRef = db.collection("screen_streams").document(cleanChildId)
        return try {
            val existingQuery = db.collection("screen_streams")
                .whereEqualTo("childId", cleanChildId)
                .whereArrayContains("memberUids", uid)
                .limit(1)
                .get()
                .await()
            val existingDoc = existingQuery.documents.firstOrNull()
            val members = normalizedMembers(uid, memberUids)
            val safeFrame = frameBase64.take(395000)

            if (existingDoc == null) {
                val payload = mapOf(
                    "childId" to cleanChildId,
                    "ownerUid" to uid,
                    "memberUids" to members,
                    "childName" to childName.ifBlank { "Child" }.take(100),
                    "foregroundApp" to foregroundApp.take(200),
                    "frameBase64" to safeFrame,
                    "width" to width.coerceIn(0, 2160),
                    "height" to height.coerceIn(0, 4096),
                    "isStreaming" to isStreaming,
                    "watchRequested" to isStreaming,
                    "requestedByUid" to "",
                    "fps" to fps.coerceIn(0.0, 60.0),
                    "capturedAtMs" to System.currentTimeMillis(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            } else {
                val existingMembers = (existingDoc.get("memberUids") as? List<*>)
                    ?.filterIsInstance<String>()
                    .orEmpty()
                val mergedMembers = (existingMembers + members).distinct().take(10)
                val updates = mapOf(
                    "childName" to childName.ifBlank { "Child" }.take(100),
                    "foregroundApp" to foregroundApp.take(200),
                    "frameBase64" to safeFrame,
                    "width" to width.coerceIn(0, 2160),
                    "height" to height.coerceIn(0, 4096),
                    "isStreaming" to isStreaming,
                    "fps" to fps.coerceIn(0.0, 60.0),
                    "capturedAtMs" to System.currentTimeMillis(),
                    "memberUids" to mergedMembers,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun stopScreenStream(childId: String): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Must sign in with Google first."))
        val cleanChildId = sanitizeFirestoreId(childId)
        val docRef = db.collection("screen_streams").document(cleanChildId)
        return try {
            val snap = db.collection("screen_streams")
                .whereEqualTo("childId", cleanChildId)
                .whereArrayContains("memberUids", uid)
                .limit(1)
                .get()
                .await()
            if (!snap.isEmpty) {
                docRef.update(
                    mapOf(
                        "isStreaming" to false,
                        "watchRequested" to false,
                        "fps" to 0.0,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    // -----------------------------------------------------------------
    // App Usage Stats (/app_usage_stats/{statId})
    // -----------------------------------------------------------------

    fun observeAppUsageStats(subjectUserId: String, dateKey: String): Flow<List<AppUsageStatRecord>> = flow {
        val uid = requireUserId()
        val path = "app_usage_stats"
        emitAll(
            db.collection(path)
                .whereEqualTo("subjectUserId", subjectUserId)
                .whereEqualTo("dateKey", dateKey)
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        AppUsageStatRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.foregroundTimeMs }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun syncUsageStatsBatch(
        subjectUserId: String,
        role: String,
        dateKey: String,
        memberUids: List<String>,
        entries: List<AppUsageEntry>
    ): Result<Int> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Must sign in with Google first."))
        if (entries.isEmpty()) return Result.success(0)

        val safeRole = if (role.equals("parent", ignoreCase = true)) "parent" else "child"
        val members = if (safeRole == "parent") listOf(uid) else normalizedMembers(uid, memberUids)
        val cleanDate = dateKey.take(10)
        var syncedCount = 0

        return try {
            // Query existing docs owned/shared for this subject+date so updates preserve createdAt
            val existingDocs = db.collection("app_usage_stats")
                .whereEqualTo("subjectUserId", subjectUserId)
                .whereEqualTo("dateKey", cleanDate)
                .whereArrayContains("memberUids", uid)
                .get()
                .await()
                .documents
                .associateBy { it.id }

            val batch = db.batch()
            entries.take(25).forEach { entry ->
                val rawKey = "${subjectUserId}_${cleanDate}_${sha256Hex(entry.packageName).take(12)}"
                val docId = sanitizeFirestoreId(rawKey)
                val docRef = db.collection("app_usage_stats").document(docId)
                val clampedMinutes = entry.totalTimeMinutes.coerceIn(0, 1440)
                val clampedMs = entry.foregroundTimeMs.coerceIn(0L, 86_400_000L)
                val clampedLaunches = entry.launchCount.coerceIn(0, 50000)

                if (existingDocs.containsKey(docId)) {
                    batch.update(
                        docRef,
                        mapOf(
                            "appName" to entry.appName.ifBlank { entry.packageName }.take(128),
                            "category" to entry.category.ifBlank { "Application" }.take(64),
                            "totalTimeMinutes" to clampedMinutes,
                            "foregroundTimeMs" to clampedMs,
                            "launchCount" to clampedLaunches,
                            "isBlocked" to entry.isBlocked,
                            "memberUids" to members,
                            "role" to safeRole,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                } else {
                    batch.set(
                        docRef,
                        mapOf(
                            "subjectUserId" to subjectUserId.take(128),
                            "ownerUid" to uid,
                            "memberUids" to members,
                            "role" to safeRole,
                            "dateKey" to cleanDate,
                            "packageName" to entry.packageName.take(200),
                            "appName" to entry.appName.ifBlank { entry.packageName }.take(128),
                            "category" to entry.category.ifBlank { "Application" }.take(64),
                            "totalTimeMinutes" to clampedMinutes,
                            "foregroundTimeMs" to clampedMs,
                            "launchCount" to clampedLaunches,
                            "isBlocked" to entry.isBlocked,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                syncedCount++
            }
            if (syncedCount > 0) {
                batch.commit().await()
            }
            Result.success(syncedCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "app_usage_stats")
            Result.failure(e)
        }
    }
}
