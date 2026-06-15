package com.google.mediapipe.examples.poselandmarker.fragment

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
import com.google.mediapipe.examples.poselandmarker.CalibrationProfile
import com.google.mediapipe.examples.poselandmarker.CameraOwnership
import com.google.mediapipe.examples.poselandmarker.HeadUpRepository
import com.google.mediapipe.examples.poselandmarker.HeadUpService
import com.google.mediapipe.examples.poselandmarker.MainActivity
import com.google.mediapipe.examples.poselandmarker.MainViewModel
import com.google.mediapipe.examples.poselandmarker.PoseLandmarkerHelper
import com.google.mediapipe.examples.poselandmarker.PostureAnalyzer
import com.google.mediapipe.examples.poselandmarker.PostureMetrics
import com.google.mediapipe.examples.poselandmarker.PostureZone
import com.google.mediapipe.examples.poselandmarker.R
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentCameraBinding
import com.google.mediapipe.examples.poselandmarker.databinding.ItemPostureResultBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener, SensorEventListener {
    companion object {
        private const val TAG = "HeadUpScan"
        private const val CALIBRATION_SECONDS = 3
    }

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var backgroundExecutor: ExecutorService
    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT
    private var ownershipToken = -1L
    private var viewReady = false
    private var helperReady = false
    private var bindingCamera = false

    private var sensorManager: SensorManager? = null
    private var gravitySensor: Sensor? = null
    private var lastDeviceTilt = 0
    private var isDeviceFlat = false

    private var latestMetrics: PostureMetrics? = null
    private var isCalibrating = false
    private var pendingCalibrationRequest = false
    private val calibrationSamples = mutableListOf<PostureMetrics>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        backgroundExecutor = Executors.newSingleThreadExecutor()
        binding.viewFinder.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        initializeResultRows()
        val state = HeadUpRepository.currentState(requireContext())
        renderMetrics(state.metrics)
        updateAlarmUi(state.isAlarmEnabled)

        binding.calibrationButton.setOnClickListener { startCalibration() }
        binding.switchCameraButton.setOnClickListener { switchCamera() }
        binding.alarmToggleButton.setOnClickListener { toggleAlarm() }
        binding.viewFinder.doOnLayout {
            viewReady = true
            maybeStartCamera()
        }

        backgroundExecutor.execute {
            poseLandmarkerHelper = createPoseLandmarkerHelper()
            helperReady = true
            activity?.runOnUiThread { maybeStartCamera() }
        }
    }

    override fun onResume() {
        super.onResume()
        setupSensors()
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(R.id.action_camera_to_permissions)
            return
        }

        HeadUpRepository.setForegroundScanActive(requireContext(), true)
        pendingCalibrationRequest = HeadUpRepository.consumeCalibrationRequest(requireContext())
        ownershipToken = CameraOwnership.claim(CameraOwnership.Owner.FOREGROUND_SCAN)
        (activity as? MainActivity)?.startHeadUpService(HeadUpService.ACTION_PAUSE_CAMERA)

        if (this::backgroundExecutor.isInitialized && !backgroundExecutor.isShutdown) {
            backgroundExecutor.execute {
                if (!this::poseLandmarkerHelper.isInitialized) {
                    poseLandmarkerHelper = createPoseLandmarkerHelper()
                } else if (poseLandmarkerHelper.isClose()) {
                    poseLandmarkerHelper.setupPoseLandmarker()
                }
                helperReady = true
                activity?.runOnUiThread { maybeStartCamera() }
            }
        }
        maybeStartCamera()
    }

    override fun onPause() {
        releaseForegroundCamera(handOffToService = true)
        sensorManager?.unregisterListener(this)
        super.onPause()
    }

    override fun onDestroyView() {
        releaseForegroundCamera(handOffToService = false)
        mainHandler.removeCallbacksAndMessages(null)
        if (this::backgroundExecutor.isInitialized) {
            backgroundExecutor.shutdown()
            try {
                backgroundExecutor.awaitTermination(2, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        _binding = null
        super.onDestroyView()
    }

    private fun createPoseLandmarkerHelper(): PoseLandmarkerHelper = PoseLandmarkerHelper(
        context = requireContext().applicationContext,
        runningMode = RunningMode.LIVE_STREAM,
        minPoseDetectionConfidence = viewModel.currentMinPoseDetectionConfidence,
        minPoseTrackingConfidence = viewModel.currentMinPoseTrackingConfidence,
        minPosePresenceConfidence = viewModel.currentMinPosePresenceConfidence,
        currentDelegate = viewModel.currentDelegate,
        currentModel = viewModel.currentModel,
        poseLandmarkerHelperListener = this,
    )

    private fun maybeStartCamera() {
        if (!isResumed || !viewReady || !helperReady || _binding == null || bindingCamera) return
        if (!CameraOwnership.isCurrent(CameraOwnership.Owner.FOREGROUND_SCAN, ownershipToken)) return
        bindingCamera = true
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener(
            {
                bindingCamera = false
                if (!isResumed || _binding == null ||
                    !CameraOwnership.isCurrent(CameraOwnership.Owner.FOREGROUND_SCAN, ownershipToken)
                ) {
                    return@addListener
                }
                cameraProvider = future.get()
                bindCameraUseCases()
            },
            ContextCompat.getMainExecutor(requireContext()),
        )
    }

    @SuppressLint("MissingPermission", "UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val currentBinding = _binding ?: return
        if (!isResumed || !CameraOwnership.isCurrent(CameraOwnership.Owner.FOREGROUND_SCAN, ownershipToken)) return

        imageAnalyzer?.clearAnalyzer()
        provider.unbindAll()

        val selector = CameraSelector.Builder().requireLensFacing(cameraFacing).build()
        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(currentBinding.viewFinder.display.rotation)
            .build()
            .also { it.setSurfaceProvider(currentBinding.viewFinder.surfaceProvider) }

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(currentBinding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(backgroundExecutor) { imageProxy ->
                    analyzeFrame(imageProxy)
                }
            }

        try {
            camera = provider.bindToLifecycle(viewLifecycleOwner, selector, preview, imageAnalyzer)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to bind foreground camera", error)
            currentBinding.scanStatusText.setText(R.string.camera_start_failed)
        }
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            if (!helperReady || poseLandmarkerHelper.isClose() ||
                !CameraOwnership.isCurrent(CameraOwnership.Owner.FOREGROUND_SCAN, ownershipToken)
            ) return
            poseLandmarkerHelper.detectLiveStream(
                imageProxy = imageProxy,
                isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT,
            )
        } catch (error: Throwable) {
            Log.e(TAG, "Pose frame failed", error)
        } finally {
            imageProxy.close()
        }
    }

    private fun releaseForegroundCamera(handOffToService: Boolean) {
        isCalibrating = false
        calibrationSamples.clear()
        helperReady = false
        imageAnalyzer?.clearAnalyzer()
        imageAnalyzer = null
        preview = null
        camera = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        CameraOwnership.release(CameraOwnership.Owner.FOREGROUND_SCAN)

        if (this::poseLandmarkerHelper.isInitialized &&
            this::backgroundExecutor.isInitialized && !backgroundExecutor.isShutdown
        ) {
            viewModel.setMinPoseDetectionConfidence(poseLandmarkerHelper.minPoseDetectionConfidence)
            viewModel.setMinPoseTrackingConfidence(poseLandmarkerHelper.minPoseTrackingConfidence)
            viewModel.setMinPosePresenceConfidence(poseLandmarkerHelper.minPosePresenceConfidence)
            viewModel.setDelegate(poseLandmarkerHelper.currentDelegate)
            viewModel.setModel(poseLandmarkerHelper.currentModel)
            backgroundExecutor.execute { poseLandmarkerHelper.clearPoseLandmarker() }
        }
        PostureAnalyzer.resetSmoothing()

        if (handOffToService && context != null) {
            HeadUpRepository.setForegroundScanActive(requireContext(), false)
            (activity as? MainActivity)?.startHeadUpService(HeadUpService.ACTION_RESUME_CAMERA)
        }
    }

    private fun switchCamera() {
        cameraFacing = if (cameraFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        PostureAnalyzer.resetSmoothing()
        binding.overlay.clear()
        bindCameraUseCases()
        Toast.makeText(
            requireContext(),
            if (cameraFacing == CameraSelector.LENS_FACING_FRONT) R.string.camera_front else R.string.camera_back,
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun toggleAlarm() {
        val enabled = !HeadUpRepository.isAlarmEnabled(requireContext())
        HeadUpRepository.setAlarmEnabled(requireContext(), enabled)
        updateAlarmUi(enabled)
        Toast.makeText(
            requireContext(),
            if (enabled) "Alarm Sound Enabled" else "Alarm Sound Disabled",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateAlarmUi(enabled: Boolean) {
        binding.alarmToggleButton.setImageResource(
            if (enabled) R.drawable.ic_notifications_active else R.drawable.ic_notifications_off
        )
    }

    private fun startCalibration() {
        if (isCalibrating) return
        if (latestMetrics == null) {
            Toast.makeText(requireContext(), R.string.calibration_wait_for_pose, Toast.LENGTH_SHORT).show()
            return
        }
        isCalibrating = true
        calibrationSamples.clear()
        binding.calibrationButton.isEnabled = false
        runCalibrationCountdown(CALIBRATION_SECONDS)
    }

    private fun runCalibrationCountdown(secondsLeft: Int) {
        val currentBinding = _binding ?: return
        if (!isCalibrating) return
        if (secondsLeft > 0) {
            currentBinding.calibrationButton.text = getString(R.string.calibration_countdown, secondsLeft)
            mainHandler.postDelayed({ runCalibrationCountdown(secondsLeft - 1) }, 1_000L)
            return
        }

        val validSamples = calibrationSamples.filter { it.landmarkConfidence >= 0.35f }
        if (validSamples.isEmpty()) {
            Toast.makeText(requireContext(), R.string.calibration_failed, Toast.LENGTH_SHORT).show()
        } else {
            val profile = CalibrationProfile(
                angleDegrees = validSamples.map { it.rawAngleDegrees }.average().toFloat(),
                postureRatio = validSamples.map { it.postureRatio }.average().toFloat(),
                shoulderWidth = validSamples.map { it.shoulderWidth }.average().toFloat(),
            )
            HeadUpRepository.setCalibration(requireContext(), profile)
            Toast.makeText(requireContext(), R.string.calibration_complete, Toast.LENGTH_SHORT).show()
        }
        isCalibrating = false
        calibrationSamples.clear()
        currentBinding.calibrationButton.isEnabled = true
        currentBinding.calibrationButton.setText(R.string.calibrate_three_seconds)
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        val result = resultBundle.results.firstOrNull() ?: return
        val landmarks = result.landmarks().firstOrNull()
        val metrics = landmarks?.let {
            PostureAnalyzer.analyzeMediaPipe(
                landmarks = it,
                deviceTilt = lastDeviceTilt,
                isFlat = isDeviceFlat,
                calibration = HeadUpRepository.getCalibration(requireContext()),
            )
        }

        if (metrics != null) {
            latestMetrics = metrics
            if (isCalibrating) calibrationSamples += metrics
            HeadUpRepository.recordMetrics(requireContext(), metrics, source = "foreground")
            if (pendingCalibrationRequest && !isCalibrating) {
                pendingCalibrationRequest = false
                activity?.runOnUiThread { startCalibration() }
            }
        }

        activity?.runOnUiThread {
            val currentBinding = _binding ?: return@runOnUiThread
            if (metrics != null) renderMetrics(metrics)
            currentBinding.overlay.setResults(
                result,
                resultBundle.inputImageHeight,
                resultBundle.inputImageWidth,
                RunningMode.LIVE_STREAM,
                metrics?.zone ?: PostureZone.SAFE,
            )
        }
    }

    private fun initializeResultRows() {
        bindResultRow(binding.headTiltRow, "H", getString(R.string.head_forward_tilt), getString(R.string.reading), "--", R.color.headup_text_secondary)
        bindResultRow(binding.neckCurvatureRow, "N", getString(R.string.neck_curve), getString(R.string.reading), "--", R.color.headup_text_secondary)
        bindResultRow(binding.shoulderBalanceRow, "S", getString(R.string.shoulder_balance), getString(R.string.reading), "--", R.color.headup_text_secondary)
        bindResultRow(binding.screenDistanceRow, "D", getString(R.string.screen_distance), getString(R.string.reading), "--", R.color.headup_text_secondary)
    }

    private fun renderMetrics(metrics: PostureMetrics) {
        val currentBinding = _binding ?: return
        val colorRes = metrics.zone.colorRes()
        currentBinding.scanProgress.progress = (100 - metrics.angleDegrees * 2).coerceIn(0, 100)
        currentBinding.scanStatusText.text = when (metrics.zone) {
            PostureZone.SAFE -> getString(R.string.posture_safe_format, metrics.angleDegrees)
            PostureZone.WARNING -> getString(R.string.posture_warning_format, metrics.angleDegrees)
            PostureZone.DANGER -> getString(R.string.posture_danger_format, metrics.angleDegrees)
        }
        currentBinding.scanStatusText.setTextColor(ContextCompat.getColor(requireContext(), colorRes))

        bindResultRow(currentBinding.headTiltRow, "H", getString(R.string.head_forward_tilt), localizedHeadTilt(metrics), "${metrics.relativeAngleDegrees}\u00B0", colorRes)
        bindResultRow(currentBinding.neckCurvatureRow, "N", getString(R.string.neck_curve), localizedNeck(metrics), "${metrics.neckFlexionDegrees}\u00B0", colorRes)
        bindResultRow(
            currentBinding.shoulderBalanceRow,
            "S",
            getString(R.string.shoulder_balance),
            if (metrics.shoulderBalanceDegrees < 8) getString(R.string.posture_balanced) else getString(R.string.posture_unbalanced),
            "${metrics.shoulderBalanceDegrees}\u00B0",
            if (metrics.shoulderBalanceDegrees < 8) R.color.headup_safe else R.color.headup_warning,
        )
        bindResultRow(
            currentBinding.screenDistanceRow,
            "D",
            getString(R.string.screen_distance),
            when {
                metrics.screenDistanceCm == null -> getString(R.string.distance_requires_calibration)
                metrics.isTooClose -> getString(R.string.distance_too_close)
                else -> getString(R.string.distance_normal)
            },
            metrics.screenDistanceCm?.let { getString(R.string.centimeters_format, it) } ?: "--",
            if (metrics.isTooClose) R.color.headup_warning else R.color.headup_safe,
        )
    }

    private fun localizedHeadTilt(metrics: PostureMetrics): String = when {
        metrics.relativeAngleDegrees >= 25 -> getString(R.string.posture_large_tilt)
        metrics.relativeAngleDegrees >= 15 -> getString(R.string.posture_mild_tilt)
        else -> getString(R.string.posture_normal)
    }

    private fun localizedNeck(metrics: PostureMetrics): String = when {
        metrics.neckFlexionDegrees >= 25 -> getString(R.string.posture_poor)
        metrics.neckFlexionDegrees >= 15 -> getString(R.string.posture_alert)
        else -> getString(R.string.posture_normal)
    }

    private fun bindResultRow(
        row: ItemPostureResultBinding,
        icon: String,
        title: String,
        value: String,
        angle: String,
        colorRes: Int,
    ) {
        row.resultIcon.text = icon
        row.resultTitle.text = title
        row.resultValue.text = value
        row.resultAngle.text = angle
        val color = ContextCompat.getColor(requireContext(), colorRes)
        row.resultValue.setTextColor(color)
        row.resultAngle.setTextColor(color)
    }

    private fun PostureZone.colorRes(): Int = when (this) {
        PostureZone.SAFE -> R.color.headup_safe
        PostureZone.WARNING -> R.color.headup_warning
        PostureZone.DANGER -> R.color.headup_danger
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        _binding?.let { imageAnalyzer?.targetRotation = it.viewFinder.display.rotation }
    }

    private fun setupSensors() {
        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
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

    override fun onError(error: String, errorCode: Int) {
        Log.e(TAG, "MediaPipe error: $error")
        activity?.runOnUiThread {
            context?.let { Toast.makeText(it, error, Toast.LENGTH_SHORT).show() }
        }
    }
}
