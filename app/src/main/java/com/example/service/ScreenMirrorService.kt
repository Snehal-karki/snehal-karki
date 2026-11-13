package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.FocusSenseApplication
import com.example.MainActivity
import com.example.R
import com.example.data.remote.SupabaseClient
import com.example.data.repository.FirebaseLiveRepository
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class ScreenMirrorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var watchObserverJob: Job? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var cachedMemberUids: List<String> = emptyList()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system or user")
            stopCaptureAndSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCaptureAndSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val app = application as? FocusSenseApplication
                val user = app?.repository?.currentUser?.value
                val firebaseUid = Firebase.auth.currentUser?.uid
                if (user == null || user.role != "child" || firebaseUid.isNullOrBlank()) {
                    Log.w(TAG, "ScreenMirrorService rejected: requires authenticated child and Google Firebase session")
                    stopCaptureAndSelf()
                    return START_NOT_STICKY
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    Log.w(TAG, "Missing MediaProjection permission result")
                    stopCaptureAndSelf()
                    return START_NOT_STICKY
                }

                startAsForegroundService(user.name)
                beginProjection(resultCode, resultData, user.userId, user.name, user.groupId, firebaseUid)
                return START_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    private fun startAsForegroundService(childName: String) {
        ensureNotificationChannel()
        val notification = buildNotification("Live Screen Share Active ($childName)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun beginProjection(
        resultCode: Int,
        resultData: Intent,
        childId: String,
        childName: String,
        familyId: String,
        firebaseUid: String
    ) {
        cleanupMediaProjection()
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mgr.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            stopCaptureAndSelf()
            return
        }
        projection.registerCallback(projectionCallback, null)
        mediaProjection = projection

        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val rawWidth = metrics.widthPixels.coerceAtLeast(360)
        val rawHeight = metrics.heightPixels.coerceAtLeast(640)
        val targetWidth = 360
        val targetHeight = ((rawHeight.toFloat() / rawWidth.toFloat()) * targetWidth).toInt().coerceIn(480, 960)
        val density = metrics.densityDpi.coerceAtLeast(160)

        val reader = ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = projection.createVirtualDisplay(
            "FocusSenseMirror",
            targetWidth,
            targetHeight,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )

        _isMirroringActive.value = true
        val liveRepo = FirebaseLiveRepository(applicationContext)

        watchObserverJob?.cancel()
        watchObserverJob = serviceScope.launch {
            // Observe watchRequested flag so if Parent clicks Stop remotely, service stops cleanly
            try {
                liveRepo.observeScreenStream(childId).collectLatest { session ->
                    if (session != null && session.isStreaming && !session.watchRequested) {
                        // Allow initial grace period right after child starts before honoring remote stop
                        if (System.currentTimeMillis() - sessionStartedAtMs > 5000L) {
                            Log.i(TAG, "Parent stopped watch request; ending screen mirror")
                            stopCaptureAndSelf()
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        sessionStartedAtMs = System.currentTimeMillis()
        captureJob?.cancel()
        captureJob = serviceScope.launch {
            cachedMemberUids = (listOf(firebaseUid) + SupabaseClient.fetchFamilyFirebaseUids(familyId))
                .distinct()
                .take(10)

            var lastMembersRefresh = System.currentTimeMillis()
            while (isActive) {
                try {
                    if (System.currentTimeMillis() - lastMembersRefresh > 15_000L) {
                        cachedMemberUids = (listOf(firebaseUid) + SupabaseClient.fetchFamilyFirebaseUids(familyId))
                            .distinct()
                            .take(10)
                        lastMembersRefresh = System.currentTimeMillis()
                    }

                    val image = imageReader?.acquireLatestImage()
                    if (image != null) {
                        var bitmap: Bitmap? = null
                        var cropped: Bitmap? = null
                        try {
                            val planes = image.planes
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding = rowStride - pixelStride * targetWidth
                            val fullWidth = targetWidth + rowPadding / pixelStride
                            bitmap = Bitmap.createBitmap(fullWidth, targetHeight, Bitmap.Config.ARGB_8888)
                            bitmap.copyPixelsFromBuffer(buffer)
                            cropped = if (fullWidth != targetWidth) {
                                Bitmap.createBitmap(bitmap, 0, 0, targetWidth, targetHeight)
                            } else {
                                bitmap
                            }
                            val baos = ByteArrayOutputStream()
                            cropped.compress(Bitmap.CompressFormat.JPEG, 46, baos)
                            val base64Frame = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

                            liveRepo.pushScreenFrame(
                                childId = childId,
                                childName = childName,
                                foregroundApp = FocusSenseAccessibilityService.currentForegroundAppLabel.value,
                                frameBase64 = base64Frame,
                                width = targetWidth,
                                height = targetHeight,
                                isStreaming = true,
                                fps = 0.85,
                                memberUids = cachedMemberUids
                            )
                        } finally {
                            if (cropped != null && cropped !== bitmap) {
                                cropped.recycle()
                            }
                            bitmap?.recycle()
                            image.close()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Frame capture loop error: ${e.message}")
                }
                delay(1200L)
            }
        }
    }

    private var sessionStartedAtMs: Long = 0L

    private fun stopCaptureAndSelf() {
        _isMirroringActive.value = false
        captureJob?.cancel()
        watchObserverJob?.cancel()
        val app = application as? FocusSenseApplication
        val childId = app?.repository?.currentUser?.value?.userId
        if (!childId.isNullOrBlank() && Firebase.auth.currentUser != null) {
            serviceScope.launch {
                try {
                    FirebaseLiveRepository(applicationContext).stopScreenStream(childId)
                } catch (_: Exception) {
                }
            }
        }
        cleanupMediaProjection()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupMediaProjection() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
    }

    override fun onDestroy() {
        _isMirroringActive.value = false
        cleanupMediaProjection()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_MIRROR,
                "FocusSense Live Screen Protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active while Parent watches the Child screen in real time"
            }
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            101,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, ScreenMirrorService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this,
            102,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_MIRROR)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("FocusSense Screen View Active")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .addAction(0, "Stop Sharing", stopPi)
            .build()
    }

    companion object {
        private const val TAG = "ScreenMirrorService"
        private const val CHANNEL_MIRROR = "focussense_screen_mirror"
        private const val NOTIFICATION_ID = 4102

        const val ACTION_START = "com.example.action.START_SCREEN_MIRROR"
        const val ACTION_STOP = "com.example.action.STOP_SCREEN_MIRROR"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private val _isMirroringActive = MutableStateFlow(false)
        val isMirroringActive: StateFlow<Boolean> = _isMirroringActive.asStateFlow()
    }
}
