package com.google.mediapipe.examples.poselandmarker

import android.Manifest
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HeadUpService : Service(), LifecycleOwner, PoseLandmarkerHelper.LandmarkerListener, SensorEventListener {
    companion object {
        const val ACTION_PAUSE_CAMERA = "com.google.mediapipe.examples.poselandmarker.PAUSE_CAMERA"
        const val ACTION_RESUME_CAMERA = "com.google.mediapipe.examples.poselandmarker.RESUME_CAMERA"
        private const val CHANNEL_ID = "HeadUpServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "HeadUpService"
        private const val WARNING_DELAY_MS = 2_000L
        private const val RAPID_FALL_VIBRATION_MS = 100L
        private const val WARNING_VIBRATION_MS = 260L
        private const val VIBRATION_COOLDOWN_MS = 1_500L
        private const val ALARM_COOLDOWN_MS = 3_000L
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var backgroundExecutor: ExecutorService
    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private var helperReady = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var cameraOwnershipToken = -1L
    private var isCameraPaused = true

    private var windowManager: WindowManager? = null
    private var warningOverlayView: WarningBorderView? = null
    private var warningAnimator: ValueAnimator? = null
    private var sensorManager: SensorManager? = null
    private var gravitySensor: Sensor? = null
    private var lastDeviceTilt = 0
    private var isDeviceFlat = false

    private var badPostureStartTime = 0L
    private var warningActive = false
    private var wasRapidFall = false
    private var lastVibrationTime = 0L
    private var lastProcessedTimestamp = Long.MIN_VALUE
    private var toneGenerator: ToneGenerator? = null
    private var lastAlarmTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        backgroundExecutor = Executors.newSingleThreadExecutor()
        toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 80)
        createNotificationChannel()
        val state = HeadUpRepository.currentState(this)
        startForegroundCompat(buildNotification(state))
        setupWarningOverlay()
        setupSensors()

        HeadUpRepository.observeState().observe(this) { updatedState ->
            updateNotification(updatedState)
            if (isCameraPaused || HeadUpRepository.isForegroundScanActive(this)) {
                processPostureMetrics(updatedState.metrics)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_CAMERA -> pauseHiddenCamera()
            ACTION_RESUME_CAMERA -> resumeHiddenCamera()
            else -> if (HeadUpRepository.isForegroundScanActive(this)) pauseHiddenCamera() else resumeHiddenCamera()
        }
        return START_STICKY
    }

    private fun pauseHiddenCamera() {
        isCameraPaused = true
        imageAnalyzer?.clearAnalyzer()
        imageAnalyzer = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        CameraOwnership.release(CameraOwnership.Owner.BACKGROUND_SERVICE)
        helperReady = false
        if (this::poseLandmarkerHelper.isInitialized && !backgroundExecutor.isShutdown) {
            backgroundExecutor.execute { poseLandmarkerHelper.clearPoseLandmarker() }
        }
        PostureAnalyzer.resetSmoothing()
        resetDangerTimer()
    }

    private fun resumeHiddenCamera() {
        if (HeadUpRepository.isForegroundScanActive(this) ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) return

        isCameraPaused = false
        cameraOwnershipToken = CameraOwnership.claim(CameraOwnership.Owner.BACKGROUND_SERVICE)
        val token = cameraOwnershipToken
        PostureAnalyzer.resetSmoothing()
        resetDangerTimer()

        backgroundExecutor.execute {
            if (!this::poseLandmarkerHelper.isInitialized) {
                poseLandmarkerHelper = PoseLandmarkerHelper(
                    context = applicationContext,
                    runningMode = RunningMode.LIVE_STREAM,
                    poseLandmarkerHelperListener = this,
                )
            } else if (poseLandmarkerHelper.isClose()) {
                poseLandmarkerHelper.setupPoseLandmarker()
            }
            helperReady = true
            mainHandler.post { startHiddenCamera(token) }
        }
    }

    private fun startHiddenCamera(token: Long) {
        if (!canOwnBackgroundCamera(token) || !helperReady) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                val provider = future.get()
                if (!canOwnBackgroundCamera(token)) {
                    return@addListener
                }
                cameraProvider = provider
                imageAnalyzer?.clearAnalyzer()
                provider.unbindAll()

                imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(backgroundExecutor) { imageProxy ->
                            try {
                                if (canOwnBackgroundCamera(token) && helperReady && !poseLandmarkerHelper.isClose()) {
                                    poseLandmarkerHelper.detectLiveStream(imageProxy, true)
                                }
                            } catch (error: Throwable) {
                                Log.e(TAG, "Background pose frame failed", error)
                            } finally {
                                imageProxy.close()
                            }
                        }
                    }

                try {
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        imageAnalyzer,
                    )
                } catch (error: Exception) {
                    Log.e(TAG, "Unable to bind background camera", error)
                    imageAnalyzer?.clearAnalyzer()
                    provider.unbindAll()
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun canOwnBackgroundCamera(token: Long): Boolean =
        !isCameraPaused &&
            !HeadUpRepository.isForegroundScanActive(this) &&
            CameraOwnership.isCurrent(CameraOwnership.Owner.BACKGROUND_SERVICE, token)

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        if (!canOwnBackgroundCamera(cameraOwnershipToken)) return
        val landmarks = resultBundle.results.firstOrNull()?.landmarks()?.firstOrNull() ?: return
        val metrics = PostureAnalyzer.analyzeMediaPipe(
            landmarks = landmarks,
            deviceTilt = lastDeviceTilt,
            isFlat = isDeviceFlat,
            calibration = HeadUpRepository.getCalibration(this),
        ) ?: return
        processPostureMetrics(metrics)
        HeadUpRepository.recordMetrics(this, metrics, source = "background")
    }

    private fun processPostureMetrics(metrics: PostureMetrics) {
        if (metrics.timestampMs == lastProcessedTimestamp) return
        lastProcessedTimestamp = metrics.timestampMs
        mainHandler.post {
            val now = SystemClock.elapsedRealtime()
            if (metrics.zone == PostureZone.DANGER) {
                if (badPostureStartTime == 0L) badPostureStartTime = now
                if (now - badPostureStartTime >= WARNING_DELAY_MS && !warningActive) {
                    warningActive = true
                    showWarningOverlay()
                    vibrate(WARNING_VIBRATION_MS)
                    playAlarmIfNeeded()
                }
            } else {
                resetDangerTimer()
            }

            if (metrics.isRapidFall && !wasRapidFall) {
                vibrate(RAPID_FALL_VIBRATION_MS)
            }
            wasRapidFall = metrics.isRapidFall
        }
    }

    private fun resetDangerTimer() {
        badPostureStartTime = 0L
        warningActive = false
        wasRapidFall = false
        hideWarningOverlay()
    }

    private fun setupWarningOverlay() {
        if (warningOverlayView != null || !Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val overlay = WarningBorderView(this).apply { visibility = View.GONE }
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            windowManager?.addView(overlay, params)
            warningOverlayView = overlay
        } catch (error: Exception) {
            Log.e(TAG, "Unable to attach warning overlay", error)
        }
    }

    private fun showWarningOverlay() {
        if (warningOverlayView == null) setupWarningOverlay()
        val overlay = warningOverlayView ?: return
        overlay.visibility = View.VISIBLE
        if (warningAnimator?.isRunning == true) return
        warningAnimator = ValueAnimator.ofFloat(0.5f, 1f).apply {
            duration = 700L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { overlay.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun hideWarningOverlay() {
        warningAnimator?.cancel()
        warningAnimator = null
        warningOverlayView?.apply {
            alpha = 1f
            visibility = View.GONE
        }
    }

    private fun playAlarmIfNeeded() {
        if (!HeadUpRepository.isAlarmEnabled(this)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAlarmTime < ALARM_COOLDOWN_MS) return
        lastAlarmTime = now
        toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500)
    }

    private fun vibrate(durationMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastVibrationTime < VIBRATION_COOLDOWN_MS) return
        lastVibrationTime = now
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gravitySensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_GRAVITY || event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val y = event.values[1]
            val z = event.values[2]
            lastDeviceTilt = Math.toDegrees(Math.atan2(y.toDouble(), z.toDouble())).toInt()
            isDeviceFlat = kotlin.math.abs(z) > 8.5f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: HeadUpUiState) {
        if (!canPostNotifications()) return
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
        } catch (error: SecurityException) {
            Log.w(TAG, "Notification permission denied", error)
        }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun buildNotification(state: HeadUpUiState): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_guard_title))
            .setContentText(getString(R.string.notification_guard_text, state.metrics.angleDegrees, localizedStatus(state.metrics.zone)))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    private fun localizedStatus(zone: PostureZone): String = when (zone) {
        PostureZone.SAFE -> getString(R.string.posture_status_safe)
        PostureZone.WARNING -> getString(R.string.posture_status_warning)
        PostureZone.DANGER -> getString(R.string.posture_status_danger)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onError(error: String, errorCode: Int) {
        Log.e(TAG, "MediaPipe error: $error")
    }

    override fun onDestroy() {
        isCameraPaused = true
        toneGenerator?.release()
        toneGenerator = null
        sensorManager?.unregisterListener(this)
        resetDangerTimer()
        imageAnalyzer?.clearAnalyzer()
        imageAnalyzer = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        CameraOwnership.release(CameraOwnership.Owner.BACKGROUND_SERVICE)
        if (this::poseLandmarkerHelper.isInitialized) poseLandmarkerHelper.clearPoseLandmarker()
        backgroundExecutor.shutdown()
        try {
            backgroundExecutor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        warningOverlayView?.let { overlay ->
            try {
                windowManager?.removeView(overlay)
            } catch (_: Exception) {
                Unit
            }
        }
        warningOverlayView = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private class WarningBorderView(context: Context) : View(context) {
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E85B5B")
            style = Paint.Style.STROKE
            strokeWidth = 26f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = borderPaint.strokeWidth / 2f + 3f
            canvas.drawRoundRect(inset, inset, width - inset, height - inset, 28f, 28f, borderPaint)
        }
    }
}
