package com.example.data.remote

import android.util.Log
import com.example.data.model.ActivityLogEntity
import com.example.data.model.DeviceEntity
import com.example.data.model.InstalledAppEntity
import com.example.data.model.LocationPointEntity
import com.example.data.model.ScheduleRuleEntity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class SupabaseUserRecord(
    val id: String,
    val email: String,
    val username: String,
    val role: String,
    val displayName: String,
    val familyId: String?
)

object SupabaseClient {
    const val SUPABASE_URL = "https://mukkrkppvfxnbbxozvwj.supabase.co"
    const val API_KEY = "sb_publishable_qcE48d96L0OVpVVV6rQsMw_EKLpPBIi"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val registeredDeviceCache = ConcurrentHashMap.newKeySet<String>()

    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun isoNow(): String = isoFromMillis(System.currentTimeMillis())

    private fun isoFromMillis(millis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(millis))
    }

    fun millisFromIso(iso: String?): Long {
        if (iso.isNullOrBlank()) return System.currentTimeMillis()
        val normalized = iso.trim().replace("+00:00", "Z")
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss"
        )
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val parsed = sdf.parse(normalized)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {
            }
        }
        return System.currentTimeMillis()
    }

    fun toUuid(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return UUID.randomUUID().toString()
        return try {
            UUID.fromString(trimmed).toString()
        } catch (_: Exception) {
            UUID.nameUUIDFromBytes(trimmed.toByteArray(Charsets.UTF_8)).toString()
        }
    }

    private fun encodeParam(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun newAuthRequest(endpoint: String): Request.Builder {
        val fullUrl = if (endpoint.startsWith("http")) endpoint else "$SUPABASE_URL/rest/v1/$endpoint"
        return Request.Builder()
            .url(fullUrl)
            .addHeader("apikey", API_KEY)
            .addHeader("Authorization", "Bearer $API_KEY")
    }

    private fun inferCategoryFromPackage(packageName: String): String {
        val lower = packageName.lowercase()
        return when {
            lower.contains("youtube") || lower.contains("netflix") || lower.contains("video") -> "Entertainment"
            lower.contains("tiktok") || lower.contains("musically") || lower.contains("instagram") ||
                lower.contains("snapchat") || lower.contains("twitter") || lower.contains("facebook") -> "Social Media"
            lower.contains("discord") || lower.contains("whatsapp") || lower.contains("telegram") ||
                lower.contains("messenger") -> "Messaging"
            lower.contains("roblox") || lower.contains("game") || lower.contains("minecraft") ||
                lower.contains("fortnite") -> "Gaming"
            lower.contains("chrome") || lower.contains("browser") || lower.contains("firefox") -> "Browser"
            else -> "Application"
        }
    }

    /**
     * Test connection to Supabase REST API
     */
    fun ping(): Pair<Boolean, String> {
        return try {
            val req = newAuthRequest("fs_users?select=id&limit=1")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Pair(true, "Connected to Supabase Cloud (mukkrkppvfxnbbxozvwj)")
                } else {
                    Pair(false, "Supabase HTTP ${resp.code}: ${resp.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Ping error: ${e.message}", e)
            Pair(false, e.message ?: "Network error connecting to Supabase")
        }
    }

    /**
     * Ensure a hardware device row exists in fs_devices (required FK for fs_activity_logs & fs_location_logs)
     */
    fun ensureDeviceRegistered(
        deviceId: String,
        userId: String,
        deviceName: String
    ): Boolean {
        return try {
            val devUuid = toUuid(deviceId)
            val userUuid = toUuid(userId)
            val now = isoNow()

            if (registeredDeviceCache.contains(devUuid)) {
                val patchJson = JSONObject().apply {
                    put("is_active", true)
                    put("last_seen", now)
                }
                val patchReq = newAuthRequest("fs_devices?id=eq.$devUuid")
                    .addHeader("Prefer", "return=minimal")
                    .patch(patchJson.toString().toRequestBody(jsonMediaType))
                    .build()
                httpClient.newCall(patchReq).execute().use { it.close() }
                return true
            }

            val checkReq = newAuthRequest("fs_devices?id=eq.$devUuid&select=id")
                .get()
                .build()
            val exists = httpClient.newCall(checkReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    JSONArray(resp.body?.string() ?: "[]").length() > 0
                } else {
                    false
                }
            }

            val ok = if (exists) {
                val patchJson = JSONObject().apply {
                    put("user_id", userUuid)
                    put("device_name", deviceName.ifBlank { "FocusSense Device" })
                    put("is_active", true)
                    put("last_seen", now)
                }
                val patchReq = newAuthRequest("fs_devices?id=eq.$devUuid")
                    .addHeader("Prefer", "return=minimal")
                    .patch(patchJson.toString().toRequestBody(jsonMediaType))
                    .build()
                httpClient.newCall(patchReq).execute().use { it.isSuccessful }
            } else {
                val devJson = JSONObject().apply {
                    put("id", devUuid)
                    put("user_id", userUuid)
                    put("device_name", deviceName.ifBlank { "FocusSense Device" })
                    put("device_model", android.os.Build.MODEL ?: "")
                    put("os_version", "Android ${android.os.Build.VERSION.RELEASE ?: ""}")
                    put("app_version", "1.0")
                    put("fcm_token", "")
                    put("is_active", true)
                    put("last_seen", now)
                }
                val postReq = newAuthRequest("fs_devices")
                    .addHeader("Prefer", "return=minimal")
                    .post(devJson.toString().toRequestBody(jsonMediaType))
                    .build()
                httpClient.newCall(postReq).execute().use { it.isSuccessful }
            }

            if (ok) {
                registeredDeviceCache.add(devUuid)
            }
            ok
        } catch (e: Exception) {
            Log.w("SupabaseClient", "ensureDeviceRegistered error: ${e.message}")
            false
        }
    }

    fun fetchDevicesForUser(userId: String): List<DeviceEntity> {
        return try {
            val userUuid = toUuid(userId)
            val req = newAuthRequest("fs_devices?user_id=eq.$userUuid&select=*")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONArray(resp.body?.string() ?: "[]")
                val list = mutableListOf<DeviceEntity>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        DeviceEntity(
                            deviceId = obj.getString("id"),
                            userId = obj.optString("user_id", userUuid),
                            deviceName = obj.optString("device_name", "Child Device"),
                            token = obj.optString("fcm_token", ""),
                            batteryPercent = 95,
                            isOnline = obj.optBoolean("is_active", true),
                            lastActive = millisFromIso(obj.optString("last_seen"))
                        )
                    )
                }
                list
            }
        } catch (e: Exception) {
            Log.w("SupabaseClient", "fetchDevicesForUser error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Link current device's Firebase Auth UID in Supabase fs_devices.app_version so family members can discover authorized UIDs for Firestore rules.
     */
    fun registerFirebaseUidForDevice(
        deviceId: String,
        userId: String,
        deviceName: String,
        firebaseUid: String
    ): Boolean {
        if (firebaseUid.isBlank() || userId.isBlank()) return false
        return try {
            ensureDeviceRegistered(deviceId, userId, deviceName)
            val devUuid = toUuid(deviceId)
            val patchJson = JSONObject().apply {
                put("app_version", "fb:${firebaseUid.take(58)}")
                put("last_seen", isoNow())
            }
            val patchReq = newAuthRequest("fs_devices?id=eq.$devUuid")
                .addHeader("Prefer", "return=minimal")
                .patch(patchJson.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(patchReq).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w("SupabaseClient", "registerFirebaseUidForDevice error: ${e.message}")
            false
        }
    }

    /**
     * Fetch all registered Firebase Auth UIDs belonging to this Supabase family group.
     */
    fun fetchFamilyFirebaseUids(familyId: String): List<String> {
        if (familyId.isBlank()) return emptyList()
        return try {
            val familyUuid = toUuid(familyId)
            val usersReq = newAuthRequest("fs_users?family_id=eq.$familyUuid&select=id")
                .get()
                .build()
            val userIds = httpClient.newCall(usersReq).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONArray(resp.body?.string() ?: "[]")
                (0 until arr.length()).mapNotNull { i ->
                    arr.getJSONObject(i).optString("id", "").ifBlank { null }
                }
            }
            if (userIds.isEmpty()) return emptyList()

            val inFilter = userIds.joinToString(",")
            val devReq = newAuthRequest("fs_devices?user_id=in.($inFilter)&select=app_version")
                .get()
                .build()
            httpClient.newCall(devReq).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONArray(resp.body?.string() ?: "[]")
                val uids = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val ver = arr.getJSONObject(i).optString("app_version", "")
                    if (ver.startsWith("fb:")) {
                        val fbUid = ver.removePrefix("fb:").trim()
                        if (fbUid.isNotBlank()) uids.add(fbUid)
                    }
                }
                uids.distinct()
            }
        } catch (e: Exception) {
            Log.w("SupabaseClient", "fetchFamilyFirebaseUids error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Register a new Parent in Supabase
     */
    fun registerParent(
        name: String,
        familyName: String,
        email: String,
        passwordHash: String,
        deviceId: String = ""
    ): Result<SupabaseUserRecord> {
        return try {
            val cleanEmail = email.trim().lowercase()
            val cleanName = name.trim()
            val cleanFamilyName = if (familyName.isNotBlank()) familyName.trim() else "$cleanName's Family"
            val now = isoNow()

            // Check if user already exists
            val checkReq = newAuthRequest("fs_users?email=eq.${encodeParam(cleanEmail)}&select=id,email")
                .get()
                .build()
            httpClient.newCall(checkReq).execute().use { checkResp ->
                if (checkResp.isSuccessful) {
                    val existing = JSONArray(checkResp.body?.string() ?: "[]")
                    if (existing.length() > 0) {
                        return Result.failure(Exception("An account with email '$cleanEmail' already exists. Please sign in."))
                    }
                }
            }

            // 1. Create Family Group with 6-digit pairing code
            val familyId = UUID.randomUUID().toString()
            val inviteCode = (100000..999999).random().toString()
            val groupJson = JSONObject().apply {
                put("id", familyId)
                put("family_name", cleanFamilyName)
                put("invite_code", inviteCode)
                put("created_at", now)
            }

            val groupReq = newAuthRequest("fs_family_groups")
                .addHeader("Prefer", "return=representation")
                .post(groupJson.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(groupReq).execute().use { groupResp ->
                if (!groupResp.isSuccessful) {
                    val err = groupResp.body?.string() ?: ""
                    Log.e("SupabaseClient", "Failed to create family group: $err")
                    return Result.failure(Exception("Supabase family creation failed: $err"))
                }
            }

            // 2. Create Parent User in fs_users
            val userId = UUID.randomUUID().toString()
            val userJson = JSONObject().apply {
                put("id", userId)
                put("email", cleanEmail)
                put("username", cleanEmail)
                put("password", passwordHash)
                put("role", "PARENT")
                put("display_name", cleanName)
                put("family_id", familyId)
                put("is_active", true)
                put("is_staff", false)
                put("is_superuser", false)
                put("date_joined", now)
            }

            val userReq = newAuthRequest("fs_users")
                .addHeader("Prefer", "return=representation")
                .post(userJson.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(userReq).execute().use { userResp ->
                if (!userResp.isSuccessful) {
                    val err = userResp.body?.string() ?: ""
                    Log.e("SupabaseClient", "Failed to create user in fs_users: $err")
                    return Result.failure(Exception("Supabase user creation failed: $err"))
                }
            }

            if (deviceId.isNotBlank()) {
                ensureDeviceRegistered(deviceId, userId, "$cleanName's Parent Phone")
            }

            val record = SupabaseUserRecord(
                id = userId,
                email = cleanEmail,
                username = cleanEmail,
                role = "parent",
                displayName = cleanName,
                familyId = familyId
            )
            Result.success(record)
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Parent register error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Sign in Parent from Supabase fs_users
     */
    fun loginParent(
        email: String,
        passwordExpected: String
    ): Result<SupabaseUserRecord> {
        return try {
            val cleanEmail = email.trim().lowercase()
            val req = newAuthRequest("fs_users?email=eq.${encodeParam(cleanEmail)}&role=eq.PARENT&select=*")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Result.failure(Exception("Supabase query failed: HTTP ${resp.code}"))
                }
                val arr = JSONArray(resp.body?.string() ?: "[]")
                if (arr.length() == 0) {
                    return Result.failure(Exception("No parent account found for '$cleanEmail'."))
                }
                val obj = arr.getJSONObject(0)
                val dbPass = obj.optString("password")
                if (dbPass != passwordExpected) {
                    return Result.failure(Exception("Incorrect password. Please verify and try again."))
                }

                val record = SupabaseUserRecord(
                    id = obj.getString("id"),
                    email = obj.optString("email", cleanEmail),
                    username = obj.optString("username", cleanEmail),
                    role = "parent",
                    displayName = obj.optString("display_name", cleanEmail),
                    familyId = obj.optString("family_id", "").ifBlank { null }
                )
                Result.success(record)
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Login error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Generate or refresh 6-digit family pairing code in Supabase fs_family_groups.invite_code
     */
    fun generatePairingCode(familyId: String): Result<String> {
        return try {
            val familyUuid = toUuid(familyId)
            val newCode = (100000..999999).random().toString()
            val patchJson = JSONObject().apply {
                put("invite_code", newCode)
            }
            val req = newAuthRequest("fs_family_groups?id=eq.$familyUuid")
                .addHeader("Prefer", "return=representation")
                .patch(patchJson.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string() ?: "HTTP ${resp.code}"
                    return Result.failure(Exception("Failed to save pairing code: $err"))
                }
                val arr = JSONArray(resp.body?.string() ?: "[]")
                if (arr.length() == 0) {
                    // Fallback: create family group row if missing locally
                    val insertJson = JSONObject().apply {
                        put("id", familyUuid)
                        put("family_name", "FocusSense Family")
                        put("invite_code", newCode)
                        put("created_at", isoNow())
                    }
                    val insertReq = newAuthRequest("fs_family_groups")
                        .addHeader("Prefer", "return=minimal")
                        .post(insertJson.toString().toRequestBody(jsonMediaType))
                        .build()
                    httpClient.newCall(insertReq).execute().use { insResp ->
                        if (!insResp.isSuccessful) {
                            return Result.failure(Exception("Could not create family pairing record (HTTP ${insResp.code})"))
                        }
                    }
                }
                Result.success(newCode)
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "generatePairingCode error: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun getActivePairingCode(familyId: String): String? {
        return try {
            val familyUuid = toUuid(familyId)
            val req = newAuthRequest("fs_family_groups?id=eq.$familyUuid&select=invite_code")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val arr = JSONArray(resp.body?.string() ?: "[]")
                if (arr.length() == 0) return null
                arr.getJSONObject(0).optString("invite_code", "").ifBlank { null }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Pair a Child phone using the 6-digit family pairing code in Supabase fs_family_groups
     */
    fun pairChildWithCode(
        pairingCode: String,
        childName: String,
        deviceId: String,
        deviceName: String
    ): Result<Pair<SupabaseUserRecord, String>> {
        return try {
            val cleanCode = pairingCode.trim().uppercase()
            val groupReq = newAuthRequest("fs_family_groups?invite_code=eq.${encodeParam(cleanCode)}&select=id,family_name")
                .get()
                .build()

            var familyId = ""
            httpClient.newCall(groupReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Result.failure(Exception("Supabase lookup failed (HTTP ${resp.code})"))
                }
                val arr = JSONArray(resp.body?.string() ?: "[]")
                if (arr.length() == 0) {
                    return Result.failure(Exception("Invalid pairing code. Generate a fresh 6-digit code on the Parent phone."))
                }
                familyId = arr.getJSONObject(0).getString("id")
            }

            // Look up parent of this family for created_by_id
            var parentId: String? = null
            val parentReq = newAuthRequest("fs_users?family_id=eq.$familyId&role=eq.PARENT&select=id&limit=1")
                .get()
                .build()
            httpClient.newCall(parentReq).execute().use { pResp ->
                if (pResp.isSuccessful) {
                    val pArr = JSONArray(pResp.body?.string() ?: "[]")
                    if (pArr.length() > 0) {
                        parentId = pArr.getJSONObject(0).optString("id", "").ifBlank { null }
                    }
                }
            }

            val cleanChildName = childName.trim()
            val baseUsername = cleanChildName.lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "child" }
            val familySuffix = familyId.take(6)
            val childEmail = "$baseUsername.$familySuffix@focussense.local"
            val childUsername = "${baseUsername}_$familySuffix"
            val now = isoNow()

            var childId = UUID.randomUUID().toString()
            val checkChildReq = newAuthRequest("fs_users?family_id=eq.$familyId&role=eq.CHILD&display_name=eq.${encodeParam(cleanChildName)}&select=id,email,username")
                .get()
                .build()
            var existingFound = false
            httpClient.newCall(checkChildReq).execute().use { cResp ->
                if (cResp.isSuccessful) {
                    val existing = JSONArray(cResp.body?.string() ?: "[]")
                    if (existing.length() > 0) {
                        childId = existing.getJSONObject(0).getString("id")
                        existingFound = true
                    }
                }
            }

            if (!existingFound) {
                val childJson = JSONObject().apply {
                    put("id", childId)
                    put("email", childEmail)
                    put("username", childUsername)
                    put("password", UUID.randomUUID().toString())
                    put("role", "CHILD")
                    put("display_name", cleanChildName)
                    put("family_id", familyId)
                    if (parentId != null) put("created_by_id", parentId)
                    put("is_active", true)
                    put("is_staff", false)
                    put("is_superuser", false)
                    put("date_joined", now)
                }
                val createReq = newAuthRequest("fs_users")
                    .addHeader("Prefer", "return=representation")
                    .post(childJson.toString().toRequestBody(jsonMediaType))
                    .build()
                httpClient.newCall(createReq).execute().use { cResp ->
                    if (!cResp.isSuccessful) {
                        val err = cResp.body?.string() ?: "HTTP ${cResp.code}"
                        return Result.failure(Exception("Failed to create child profile: $err"))
                    }
                }
            }

            val finalDevId = toUuid(deviceId.ifBlank { UUID.randomUUID().toString() })
            val cleanDevName = if (deviceName.isNotBlank()) deviceName.trim() else "$cleanChildName's Phone"
            ensureDeviceRegistered(finalDevId, childId, cleanDevName)

            val record = SupabaseUserRecord(
                id = childId,
                email = childEmail,
                username = childUsername,
                role = "child",
                displayName = cleanChildName,
                familyId = familyId
            )
            Result.success(Pair(record, finalDevId))
        } catch (e: Exception) {
            Log.e("SupabaseClient", "pairChildWithCode error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Child device pairing directly via Parent credentials
     */
    fun pairChildDevice(
        parentEmail: String,
        parentPasswordExpected: String,
        childName: String,
        deviceName: String,
        deviceId: String = ""
    ): Result<Pair<SupabaseUserRecord, String>> {
        return try {
            val parentRes = loginParent(parentEmail, parentPasswordExpected)
            if (parentRes.isFailure) {
                return Result.failure(parentRes.exceptionOrNull() ?: Exception("Parent authentication failed."))
            }
            val parent = parentRes.getOrThrow()
            val familyId = parent.familyId ?: return Result.failure(Exception("Parent has no family group."))

            val cleanChildName = childName.trim()
            val baseUsername = cleanChildName.lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "child" }
            val familySuffix = familyId.take(6)
            val cleanUsername = "${baseUsername}_$familySuffix"
            val childEmail = "$baseUsername.$familySuffix@focussense.local"
            val now = isoNow()

            var childId = UUID.randomUUID().toString()
            val checkChildReq = newAuthRequest("fs_users?family_id=eq.$familyId&role=eq.CHILD&display_name=eq.${encodeParam(cleanChildName)}&select=id,display_name")
                .get()
                .build()
            var existingFound = false
            httpClient.newCall(checkChildReq).execute().use { checkChildResp ->
                if (checkChildResp.isSuccessful) {
                    val existingChildren = JSONArray(checkChildResp.body?.string() ?: "[]")
                    if (existingChildren.length() > 0) {
                        childId = existingChildren.getJSONObject(0).getString("id")
                        existingFound = true
                    }
                }
            }

            if (!existingFound) {
                val childJson = JSONObject().apply {
                    put("id", childId)
                    put("email", childEmail)
                    put("username", cleanUsername)
                    put("password", parentPasswordExpected)
                    put("role", "CHILD")
                    put("display_name", cleanChildName)
                    put("family_id", familyId)
                    put("created_by_id", parent.id)
                    put("is_active", true)
                    put("is_staff", false)
                    put("is_superuser", false)
                    put("date_joined", now)
                }
                val cReq = newAuthRequest("fs_users")
                    .addHeader("Prefer", "return=representation")
                    .post(childJson.toString().toRequestBody(jsonMediaType))
                    .build()
                httpClient.newCall(cReq).execute().use { cResp ->
                    if (!cResp.isSuccessful) {
                        val err = cResp.body?.string() ?: ""
                        return Result.failure(Exception("Failed to pair child account: $err"))
                    }
                }
            }

            val finalDevId = toUuid(deviceId.ifBlank { UUID.randomUUID().toString() })
            val cleanDevName = if (deviceName.isNotBlank()) deviceName.trim() else "$cleanChildName's Device"
            ensureDeviceRegistered(finalDevId, childId, cleanDevName)

            val record = SupabaseUserRecord(
                id = childId,
                email = childEmail,
                username = cleanUsername,
                role = "child",
                displayName = cleanChildName,
                familyId = familyId
            )
            Result.success(Pair(record, finalDevId))
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Pair child error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Create Child account directly in Supabase fs_users
     */
    fun createChildAccount(
        username: String,
        displayName: String,
        familyId: String,
        parentId: String,
        passwordHash: String
    ): Result<SupabaseUserRecord> {
        return try {
            val cleanUsername = username.trim().lowercase()
            val cleanName = displayName.trim()
            val childEmail = "$cleanUsername@focussense.local"
            val now = isoNow()

            val childId = UUID.randomUUID().toString()
            val childJson = JSONObject().apply {
                put("id", childId)
                put("email", childEmail)
                put("username", cleanUsername)
                put("password", passwordHash)
                put("role", "CHILD")
                put("display_name", cleanName)
                put("family_id", toUuid(familyId))
                put("created_by_id", toUuid(parentId))
                put("is_active", true)
                put("is_staff", false)
                put("is_superuser", false)
                put("date_joined", now)
            }

            val req = newAuthRequest("fs_users")
                .addHeader("Prefer", "return=representation")
                .post(childJson.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string() ?: ""
                    Log.e("SupabaseClient", "Failed to create child: $err")
                    return Result.failure(Exception("Failed to save child in Supabase: $err"))
                }

                val record = SupabaseUserRecord(
                    id = childId,
                    email = childEmail,
                    username = cleanUsername,
                    role = "child",
                    displayName = cleanName,
                    familyId = familyId
                )
                Result.success(record)
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Create child error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Fetch children belonging to a family from Supabase
     */
    fun fetchFamilyChildren(familyId: String): List<SupabaseUserRecord> {
        return try {
            val familyUuid = toUuid(familyId)
            val req = newAuthRequest("fs_users?family_id=eq.$familyUuid&role=eq.CHILD&select=*")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONArray(resp.body?.string() ?: "[]")
                val list = mutableListOf<SupabaseUserRecord>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        SupabaseUserRecord(
                            id = obj.getString("id"),
                            email = obj.optString("email"),
                            username = obj.optString("username"),
                            role = "child",
                            displayName = obj.optString("display_name"),
                            familyId = familyUuid
                        )
                    )
                }
                list
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Fetch children error: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Installed apps & app lock policies (fs_app_policies)
     */
    fun syncInstalledApps(childId: String, apps: List<InstalledAppEntity>): Boolean {
        if (apps.isEmpty()) return true
        return try {
            val childUuid = toUuid(childId)
            // Preserve existing remote is_blocked decisions made by Parent
            val existingMap = fetchInstalledApps(childUuid).associateBy { it.packageName }
            val now = isoNow()

            val payloadArray = JSONArray()
            for (app in apps) {
                val remoteExisting = existingMap[app.packageName]
                val effectiveBlocked = remoteExisting?.isBlocked ?: app.isBlocked
                val row = JSONObject().apply {
                    put("id", toUuid("app_policy_${childUuid}_${app.packageName}"))
                    put("child_id", childUuid)
                    put("package_name", app.packageName)
                    put("app_name", app.appName.take(128))
                    put("is_blocked", effectiveBlocked)
                    put("daily_limit_minutes", 0)
                    put("version", 1)
                    put("updated_at", now)
                }
                payloadArray.put(row)
            }

            val req = newAuthRequest("fs_app_policies?on_conflict=child_id,package_name")
                .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(payloadArray.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "syncInstalledApps failed HTTP ${resp.code}: ${resp.body?.string()}")
                }
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.w("SupabaseClient", "syncInstalledApps error: ${e.message}")
            false
        }
    }

    fun fetchInstalledApps(childId: String): List<InstalledAppEntity> {
        return try {
            val childUuid = toUuid(childId)
            val req = newAuthRequest("fs_app_policies?child_id=eq.$childUuid&select=*")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONArray(resp.body?.string() ?: "[]")
                val list = mutableListOf<InstalledAppEntity>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val pkg = obj.optString("package_name", "")
                    if (pkg.isBlank()) continue
                    val appName = obj.optString("app_name", pkg)
                    list.add(
                        InstalledAppEntity(
                            id = "${childId}_$pkg",
                            childId = childId,
                            packageName = pkg,
                            appName = appName,
                            category = inferCategoryFromPackage(pkg),
                            isBlocked = obj.optBoolean("is_blocked", false),
                            isSystemApp = false,
                            lastUpdated = millisFromIso(obj.optString("updated_at"))
                        )
                    )
                }
                list
            }
        } catch (e: Exception) {
            Log.w("SupabaseClient", "fetchInstalledApps error: ${e.message}")
            emptyList()
        }
    }

    fun toggleAppBlock(childId: String, packageName: String, appName: String, isBlocked: Boolean): Boolean {
        return try {
            val childUuid = toUuid(childId)
            val now = isoNow()
            val row = JSONObject().apply {
                put("id", toUuid("app_policy_${childUuid}_$packageName"))
                put("child_id", childUuid)
                put("package_name", packageName)
                put("app_name", appName.ifBlank { packageName }.take(128))
                put("is_blocked", isBlocked)
                put("daily_limit_minutes", 0)
                put("version", 1)
                put("updated_at", now)
            }
            val req = newAuthRequest("fs_app_policies?on_conflict=child_id,package_name")
                .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(row.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w("SupabaseClient", "toggleAppBlock error: ${e.message}")
            false
        }
    }

    /**
     * Schedule & Curfew Rules (fs_schedule_rules)
     */
    fun saveScheduleRule(rule: ScheduleRuleEntity): Boolean {
        return try {
            val childUuid = toUuid(rule.childId)
            val ruleUuid = toUuid(rule.ruleId)
            val days = rule.dayOfWeek
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            val daysArray = JSONArray(days)

            val json = JSONObject().apply {
                put("id", ruleUuid)
                put("child_id", childUuid)
                put("rule_name", rule.ruleName.take(128))
                put("rule_type", rule.category.take(32))
                put("target_package", rule.restrictedPackages.take(255))
                put("days_of_week", daysArray)
                put("start_time", rule.startTime)
                put("end_time", rule.endTime)
                put("is_active", rule.isActive)
                put("version", 1)
                put("updated_at", isoNow())
            }

            val req = newAuthRequest("fs_schedule_rules?on_conflict=id")
                .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "saveScheduleRule failed HTTP ${resp.code}: ${resp.body?.string()}")
                }
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.w("SupabaseClient", "saveScheduleRule error: ${e.message}")
            false
        }
    }

    fun deleteScheduleRule(ruleId: String): Boolean {
        return try {
            val ruleUuid = toUuid(ruleId)
            val req = newAuthRequest("fs_schedule_rules?id=eq.$ruleUuid")
                .delete()
                .build()
            httpClient.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w("SupabaseClient", "deleteScheduleRule error: ${e.message}")
            false
        }
    }

    fun fetchScheduleRules(childId: String): List<ScheduleRuleEntity> {
        return try {
            val childUuid = toUuid(childId)
            val req = newAuthRequest("fs_schedule_rules?child_id=eq.$childUuid&select=*")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONArray(resp.body?.string() ?: "[]")
                val list = mutableListOf<ScheduleRuleEntity>()
                val numToDay = mapOf(
                    1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu",
                    5 to "Fri", 6 to "Sat", 7 to "Sun", 0 to "Sun"
                )
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val daysJson = obj.optJSONArray("days_of_week")
                    val daysStr = if (daysJson != null && daysJson.length() > 0) {
                        val items = mutableListOf<String>()
                        for (j in 0 until daysJson.length()) {
                            val elem = daysJson.opt(j)
                            when (elem) {
                                is Int -> numToDay[elem]?.let { items.add(it) }
                                is Number -> numToDay[elem.toInt()]?.let { items.add(it) }
                                is String -> if (elem.isNotBlank()) items.add(elem)
                            }
                        }
                        items.joinToString(",")
                    } else {
                        "Mon,Tue,Wed,Thu,Fri,Sat,Sun"
                    }

                    list.add(
                        ScheduleRuleEntity(
                            ruleId = obj.getString("id"),
                            childId = childId,
                            ruleName = obj.optString("rule_name", "Schedule Rule"),
                            category = obj.optString("rule_type", "Study"),
                            startTime = obj.optString("start_time", "00:00").take(5),
                            endTime = obj.optString("end_time", "23:59").take(5),
                            dayOfWeek = daysStr,
                            restrictedPackages = obj.optString("target_package", ""),
                            isActive = obj.optBoolean("is_active", true)
                        )
                    )
                }
                list
            }
        } catch (e: Exception) {
            Log.w("SupabaseClient", "fetchScheduleRules error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Upload telemetry activity log to Supabase fs_activity_logs
     */
    fun pushActivityLog(log: ActivityLogEntity, childId: String, deviceId: String = ""): Boolean {
        return try {
            val childUuid = toUuid(childId)
            val devUuid = toUuid(deviceId.ifBlank { "device_$childUuid" })
            ensureDeviceRegistered(devUuid, childUuid, "Child Phone")

            val logUuid = toUuid(log.logId)
            val tsIso = isoFromMillis(log.recordedAt)
            val json = JSONObject().apply {
                put("id", logUuid)
                put("device_id", devUuid)
                put("child_id", childUuid)
                put("package_name", log.packageName)
                put("app_name", log.appName.take(128))
                put("content_title", log.contentTitle)
                put("threat_category", (log.threatCategory ?: "Safe").take(64))
                put("confidence_score", log.confidenceScore.toDouble())
                put("ai_analysis_summary", log.aiAnalysisSummary)
                put("is_flagged", log.isFlagged)
                put("timestamp", tsIso)
                put("created_at", tsIso)
            }
            val req = newAuthRequest("fs_activity_logs?on_conflict=id")
                .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "pushActivityLog HTTP ${resp.code}: ${resp.body?.string()}")
                }
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.w("SupabaseClient", "Failed to upload activity log: ${e.message}")
            false
        }
    }

    /**
     * Fetch activity logs for child from Supabase fs_activity_logs
     */
    fun fetchChildLogs(childId: String): List<ActivityLogEntity> {
        return try {
            val childUuid = toUuid(childId)
            val req = newAuthRequest("fs_activity_logs?child_id=eq.$childUuid&order=timestamp.desc&limit=50")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONArray(resp.body?.string() ?: "[]")
                val list = mutableListOf<ActivityLogEntity>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        ActivityLogEntity(
                            logId = obj.getString("id"),
                            childId = childId,
                            packageName = obj.optString("package_name", ""),
                            appName = obj.optString("app_name", "App"),
                            contentTitle = obj.optString("content_title", ""),
                            extractedText = obj.optString("ai_analysis_summary", ""),
                            isFlagged = obj.optBoolean("is_flagged", false),
                            threatCategory = obj.optString("threat_category", "Safe"),
                            confidenceScore = obj.optDouble("confidence_score", 0.0).toFloat(),
                            aiAnalysisSummary = obj.optString("ai_analysis_summary", ""),
                            recordedAt = millisFromIso(obj.optString("timestamp")),
                            isSynced = true,
                            isAcknowledged = false
                        )
                    )
                }
                list
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Upload GPS location fix to Supabase fs_location_logs
     */
    fun pushLocation(point: LocationPointEntity, childId: String, deviceId: String = ""): Boolean {
        return try {
            val childUuid = toUuid(childId)
            val devUuid = toUuid(deviceId.ifBlank { "device_$childUuid" })
            ensureDeviceRegistered(devUuid, childUuid, "Child Phone")

            val locUuid = toUuid(point.locId)
            val tsIso = isoFromMillis(point.recordedAt)
            val json = JSONObject().apply {
                put("id", locUuid)
                put("device_id", devUuid)
                put("child_id", childUuid)
                put("latitude", point.latitude)
                put("longitude", point.longitude)
                put("accuracy", point.accuracy.toDouble())
                put("timestamp", tsIso)
                put("created_at", tsIso)
            }
            val req = newAuthRequest("fs_location_logs?on_conflict=id")
                .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    clearLocationPing(childId)
                } else {
                    Log.w("SupabaseClient", "pushLocation HTTP ${resp.code}: ${resp.body?.string()}")
                }
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.w("SupabaseClient", "Failed to upload location: ${e.message}")
            false
        }
    }

    /**
     * Fetch latest GPS location for child from Supabase fs_location_logs
     */
    fun fetchLatestLocation(childId: String): LocationPointEntity? {
        return try {
            val childUuid = toUuid(childId)
            val req = newAuthRequest("fs_location_logs?child_id=eq.$childUuid&order=timestamp.desc&limit=1")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val arr = JSONArray(resp.body?.string() ?: "[]")
                if (arr.length() == 0) return null
                val obj = arr.getJSONObject(0)
                val lat = obj.getDouble("latitude")
                val lng = obj.getDouble("longitude")
                LocationPointEntity(
                    locId = obj.getString("id"),
                    childId = childId,
                    latitude = lat,
                    longitude = lng,
                    accuracy = obj.optDouble("accuracy", 5.0).toFloat(),
                    locationName = "Child GPS (${String.format(Locale.US, "%.4f", lat)}, ${String.format(Locale.US, "%.4f", lng)})",
                    recordedAt = millisFromIso(obj.optString("timestamp")),
                    isSynced = true
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * On-demand GPS ping signaling via fs_devices.fcm_token
     */
    fun requestLocationPing(childId: String): Boolean {
        return try {
            val childUuid = toUuid(childId)
            val existingDevices = fetchDevicesForUser(childUuid)
            if (existingDevices.isEmpty()) {
                ensureDeviceRegistered(toUuid("device_$childUuid"), childUuid, "Child Phone")
            }
            val patchJson = JSONObject().apply {
                put("fcm_token", "PING_REQUESTED:${System.currentTimeMillis()}")
            }
            val req = newAuthRequest("fs_devices?user_id=eq.$childUuid")
                .addHeader("Prefer", "return=minimal")
                .patch(patchJson.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w("SupabaseClient", "requestLocationPing error: ${e.message}")
            false
        }
    }

    fun checkPendingLocationPing(childId: String): Boolean {
        return try {
            val childUuid = toUuid(childId)
            val req = newAuthRequest("fs_devices?user_id=eq.$childUuid&select=fcm_token")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val arr = JSONArray(resp.body?.string() ?: "[]")
                val now = System.currentTimeMillis()
                for (i in 0 until arr.length()) {
                    val token = arr.getJSONObject(i).optString("fcm_token", "")
                    if (token.startsWith("PING_REQUESTED:")) {
                        val ts = token.substringAfter("PING_REQUESTED:").toLongOrNull() ?: 0L
                        if (now - ts < 300_000L) return true
                    }
                }
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun clearLocationPing(childId: String) {
        try {
            val childUuid = toUuid(childId)
            val patchJson = JSONObject().apply {
                put("fcm_token", "")
            }
            val req = newAuthRequest("fs_devices?user_id=eq.$childUuid")
                .addHeader("Prefer", "return=minimal")
                .patch(patchJson.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(req).execute().use { it.close() }
        } catch (_: Exception) {
        }
    }
}
