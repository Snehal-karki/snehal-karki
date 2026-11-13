package com.example.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AppUsageStatRecord
import com.example.data.model.InstalledAppEntity
import com.example.data.model.ScreenStreamSession
import com.example.data.model.UserEntity
import com.example.data.remote.SupabaseClient
import com.example.data.repository.FirebaseLiveRepository
import com.example.data.repository.FocusSenseRepository
import com.example.util.AppUsageTracker
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class LiveScreenAndStatsViewModel(
    private val liveRepo: FirebaseLiveRepository,
    private val focusRepo: FocusSenseRepository,
    private val currentUserId: String
) : ViewModel() {

    private val todayDateKey = AppUsageTracker.todayDateKey()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _isSyncingUsage = MutableStateFlow(false)
    val isSyncingUsage: StateFlow<Boolean> = _isSyncingUsage.asStateFlow()

    private val activeTargetChildId: StateFlow<String> = combine(
        focusRepo.currentUser,
        focusRepo.selectedChildId
    ) { user, selectedChild ->
        when {
            user == null -> ""
            user.role == "child" -> user.userId
            else -> selectedChild
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), "")

    val screenStream: StateFlow<ScreenStreamSession?> = activeTargetChildId
        .flatMapLatest { childId ->
            if (childId.isBlank()) {
                flowOf(null)
            } else {
                liveRepo.observeScreenStream(childId)
                    .catch { error ->
                        val firestoreErr = generateSequence(error) { it.cause }
                            .filterIsInstance<FirebaseFirestoreException>()
                            .firstOrNull()
                        if (firestoreErr == null && error is CancellationException) throw error
                        val rootErr = firestoreErr ?: error
                        Log.w(TAG, "Error observing live screen stream", rootErr)
                        _statusBanner.value = rootErr.message ?: "Live stream sync error"
                        emit(null)
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    val childUsageStats: StateFlow<List<AppUsageStatRecord>> = activeTargetChildId
        .flatMapLatest { childId ->
            if (childId.isBlank()) {
                flowOf(emptyList())
            } else {
                liveRepo.observeAppUsageStats(childId, todayDateKey)
                    .catch { error ->
                        val firestoreErr = generateSequence(error) { it.cause }
                            .filterIsInstance<FirebaseFirestoreException>()
                            .firstOrNull()
                        if (firestoreErr == null && error is CancellationException) throw error
                        val rootErr = firestoreErr ?: error
                        Log.w(TAG, "Error observing child usage stats", rootErr)
                        emit(emptyList())
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val myUsageStats: StateFlow<List<AppUsageStatRecord>> = focusRepo.currentUser
        .flatMapLatest { user ->
            if (user == null) {
                flowOf(emptyList())
            } else {
                liveRepo.observeAppUsageStats(user.userId, todayDateKey)
                    .catch { error ->
                        val firestoreErr = generateSequence(error) { it.cause }
                            .filterIsInstance<FirebaseFirestoreException>()
                            .firstOrNull()
                        if (firestoreErr == null && error is CancellationException) throw error
                        val rootErr = firestoreErr ?: error
                        Log.w(TAG, "Error observing my usage stats", rootErr)
                        emit(emptyList())
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    fun syncIdentityLink(user: UserEntity, deviceId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                liveRepo.upsertUserProfile(
                    displayName = user.name,
                    role = user.role,
                    familyId = user.groupId,
                    supabaseUserId = user.userId
                )
                SupabaseClient.registerFirebaseUidForDevice(
                    deviceId = deviceId,
                    userId = user.userId,
                    deviceName = if (user.role == "parent") "${user.name}'s Parent Phone" else "${user.name}'s Child Phone",
                    firebaseUid = currentUserId
                )
            }
        }
    }

    fun requestLiveScreenWatch(childId: String, childName: String, familyId: String) {
        if (childId.isBlank()) return
        viewModelScope.launch {
            val members = withContext(Dispatchers.IO) {
                (listOf(currentUserId) + SupabaseClient.fetchFamilyFirebaseUids(familyId)).distinct()
            }
            liveRepo.requestOrStopScreenWatch(
                childId = childId,
                childName = childName,
                memberUids = members,
                watchRequested = true
            ).onFailure { err ->
                _statusBanner.value = err.localizedMessage ?: "Failed to request live view"
            }
        }
    }

    fun stopLiveScreenWatch(childId: String, childName: String, familyId: String) {
        if (childId.isBlank()) return
        viewModelScope.launch {
            val members = withContext(Dispatchers.IO) {
                (listOf(currentUserId) + SupabaseClient.fetchFamilyFirebaseUids(familyId)).distinct()
            }
            liveRepo.requestOrStopScreenWatch(
                childId = childId,
                childName = childName,
                memberUids = members,
                watchRequested = false
            )
        }
    }

    fun collectAndSyncDeviceUsageStats(
        context: Context,
        user: UserEntity,
        installedApps: List<InstalledAppEntity>,
        blockedPackages: Set<String>
    ) {
        viewModelScope.launch {
            _isSyncingUsage.value = true
            try {
                val appContext = context.applicationContext
                val entries = withContext(Dispatchers.IO) {
                    AppUsageTracker.collectTodayUsageStats(
                        context = appContext,
                        blockedPackages = blockedPackages,
                        knownApps = installedApps
                    )
                }
                if (entries.isNotEmpty()) {
                    val memberUids = withContext(Dispatchers.IO) {
                        if (user.role == "child") {
                            (listOf(currentUserId) + SupabaseClient.fetchFamilyFirebaseUids(user.groupId)).distinct()
                        } else {
                            listOf(currentUserId)
                        }
                    }
                    liveRepo.syncUsageStatsBatch(
                        subjectUserId = user.userId,
                        role = user.role,
                        dateKey = AppUsageTracker.todayDateKey(),
                        memberUids = memberUids,
                        entries = entries
                    )
                }
            } finally {
                _isSyncingUsage.value = false
            }
        }
    }

    fun clearBanner() {
        _statusBanner.value = null
    }

    private companion object {
        const val TAG = "LiveScreenAndStatsVM"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
