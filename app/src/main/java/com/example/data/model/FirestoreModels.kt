package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class UserFirebaseProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val role: String = "unassigned",
    val familyId: String = "",
    val supabaseUserId: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ScreenStreamSession(
    @DocumentId val id: String = "",
    val childId: String = "",
    val ownerUid: String = "",
    val memberUids: List<String> = emptyList(),
    val childName: String = "",
    val foregroundApp: String = "",
    val frameBase64: String = "",
    val width: Long = 0L,
    val height: Long = 0L,
    val isStreaming: Boolean = false,
    val watchRequested: Boolean = false,
    val requestedByUid: String = "",
    val fps: Double = 0.0,
    val capturedAtMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class AppUsageStatRecord(
    @DocumentId val id: String = "",
    val subjectUserId: String = "",
    val ownerUid: String = "",
    val memberUids: List<String> = emptyList(),
    val role: String = "child",
    val dateKey: String = "",
    val packageName: String = "",
    val appName: String = "",
    val category: String = "Application",
    val totalTimeMinutes: Long = 0L,
    val foregroundTimeMs: Long = 0L,
    val launchCount: Long = 0L,
    val isBlocked: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
