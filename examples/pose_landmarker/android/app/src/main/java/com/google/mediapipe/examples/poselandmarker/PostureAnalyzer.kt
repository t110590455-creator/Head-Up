package com.google.mediapipe.examples.poselandmarker

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

object PostureAnalyzer {
    private const val NOSE = 0
    private const val LEFT_EYE_INNER = 1
    private const val LEFT_EYE = 2
    private const val LEFT_EYE_OUTER = 3
    private const val RIGHT_EYE_INNER = 4
    private const val RIGHT_EYE = 5
    private const val RIGHT_EYE_OUTER = 6
    private const val LEFT_EAR = 7
    private const val RIGHT_EAR = 8
    private const val MOUTH_LEFT = 9
    private const val MOUTH_RIGHT = 10
    private const val LEFT_SHOULDER = 11
    private const val RIGHT_SHOULDER = 12
    private const val LEFT_HIP = 23
    private const val RIGHT_HIP = 24

    private const val SAFE_LIMIT_DEGREES = 15
    private const val BAD_POSTURE_LIMIT_DEGREES = 25
    private const val RAPID_FALL_ARM_DEGREES = 22
    private const val RAPID_FALL_VELOCITY = 0.5f
    private const val MIN_TRACKING_CONFIDENCE = 0.20f
    private const val DEFAULT_UPRIGHT_RATIO = 0.82f
    private const val CALIBRATED_DISTANCE_CM = 45f
    private const val TOO_CLOSE_WARNING_CM = 35
    private const val TOO_CLOSE_DANGER_CM = 25
    private const val SHOULDER_WARNING_DEGREES = 8
    private const val SHOULDER_DANGER_DEGREES = 14
    private const val NECK_RATIO_TO_DEGREES = 110f
    private const val EMA_ALPHA = 0.15f

    private var smoothedAngle: Float? = null
    private var previousSmoothedAngle: Float? = null

    @Synchronized
    fun resetSmoothing() {
        smoothedAngle = null
        previousSmoothedAngle = null
    }

    fun defaultMetrics(): PostureMetrics = PostureMetrics(
        angleDegrees = 0,
        zone = PostureZone.SAFE,
        postureRatio = 0f,
        headTiltLabel = "正常",
        neckCurvatureLabel = "正常",
        shoulderBalanceDegrees = 0,
        shoulderBalanceLabel = "平衡",
    )

    fun analyzeMediaPipe(
        landmarks: List<NormalizedLandmark>,
        deviceTilt: Int = 0,
        isFlat: Boolean = false,
        calibration: CalibrationProfile? = null,
    ): PostureMetrics? = analyze(
        points = landmarks.map { landmark ->
            LandmarkPoint(
                x = landmark.x(),
                y = landmark.y(),
                z = landmark.z(),
                visibility = landmark.visibility().orElse(1f),
                presence = landmark.presence().orElse(1f),
            )
        },
        deviceTilt = deviceTilt,
        isFlat = isFlat,
        calibration = calibration,
    )

    @Synchronized
    fun analyze(
        points: List<LandmarkPoint>,
        deviceTilt: Int = 0,
        isFlat: Boolean = false,
        calibration: CalibrationProfile? = null,
    ): PostureMetrics? {
        val body = BodyLandmarks.from(points) ?: return null
        val shoulderWidth = distance2d(body.leftShoulder, body.rightShoulder)
        if (shoulderWidth <= 0.001f) return null

        val shoulderCenter = midpoint(body.leftShoulder, body.rightShoulder)
        val faceCenter = body.faceCenter
        val verticalDistance = (shoulderCenter.y - faceCenter.y).coerceAtLeast(0.001f)
        val postureRatio = verticalDistance / shoulderWidth

        // Keep the original 15-point 3D vector core: face center to shoulder center in Y/Z space.
        val rawAngle = Math.toDegrees(
            atan2(
                (shoulderCenter.z - faceCenter.z).toDouble(),
                verticalDistance.toDouble(),
            ),
        ).toFloat().coerceIn(0f, 90f)

        val currentSmoothed = smoothedAngle?.let { previous ->
            EMA_ALPHA * rawAngle + (1f - EMA_ALPHA) * previous
        } ?: rawAngle
        val angleVelocity = previousSmoothedAngle?.let { currentSmoothed - it } ?: 0f
        previousSmoothedAngle = currentSmoothed
        smoothedAngle = currentSmoothed

        val relativeHeadAngle = (currentSmoothed - (calibration?.angleDegrees ?: 0f))
            .coerceAtLeast(0f)
        val ratioBaseline = calibration?.postureRatio
            ?.takeIf { it > 0.05f }
            ?: DEFAULT_UPRIGHT_RATIO
        val neckCompression = ((ratioBaseline - postureRatio) / ratioBaseline).coerceAtLeast(0f)
        val neckFlexion = (neckCompression * NECK_RATIO_TO_DEGREES).coerceIn(0f, 60f)
        val compositeAngle = max(relativeHeadAngle, neckFlexion)

        val shoulderBalanceAngle = pairAngleDegrees(body.leftShoulder, body.rightShoulder)
        val screenDistanceCm = calibration?.shoulderWidth
            ?.takeIf { it > 0.001f }
            ?.let { calibratedWidth ->
                (CALIBRATED_DISTANCE_CM * calibratedWidth / shoulderWidth)
                    .roundToInt()
                    .coerceIn(15, 120)
            }
        val isTooClose = screenDistanceCm?.let { it < TOO_CLOSE_WARNING_CM } ?: false
        val angle = compositeAngle.roundToInt()
        val isRapidFall = angle >= RAPID_FALL_ARM_DEGREES && angleVelocity > RAPID_FALL_VELOCITY

        val zone = when {
            angle >= BAD_POSTURE_LIMIT_DEGREES -> PostureZone.DANGER
            screenDistanceCm != null && screenDistanceCm < TOO_CLOSE_DANGER_CM -> PostureZone.DANGER
            shoulderBalanceAngle >= SHOULDER_DANGER_DEGREES -> PostureZone.DANGER
            angle >= SAFE_LIMIT_DEGREES -> PostureZone.WARNING
            isTooClose -> PostureZone.WARNING
            shoulderBalanceAngle >= SHOULDER_WARNING_DEGREES -> PostureZone.WARNING
            else -> PostureZone.SAFE
        }

        return PostureMetrics(
            angleDegrees = angle,
            rawAngleDegrees = currentSmoothed,
            relativeAngleDegrees = relativeHeadAngle.roundToInt(),
            neckFlexionDegrees = neckFlexion.roundToInt(),
            zone = zone,
            postureRatio = postureRatio,
            headTiltLabel = when {
                relativeHeadAngle >= BAD_POSTURE_LIMIT_DEGREES -> "前傾過大"
                relativeHeadAngle >= SAFE_LIMIT_DEGREES -> "輕微前傾"
                else -> "正常"
            },
            neckCurvatureLabel = when (zone) {
                PostureZone.SAFE -> "正常"
                PostureZone.WARNING -> "警戒"
                PostureZone.DANGER -> "姿勢不良"
            },
            shoulderBalanceDegrees = shoulderBalanceAngle.roundToInt(),
            shoulderBalanceLabel = if (shoulderBalanceAngle < SHOULDER_WARNING_DEGREES) "平衡" else "左右不平衡",
            screenDistanceCm = screenDistanceCm,
            isTooClose = isTooClose,
            landmarkConfidence = body.confidence,
            shoulderWidth = shoulderWidth,
            deviceTiltDegrees = deviceTilt,
            isDeviceFlat = isFlat,
            isRapidFall = isRapidFall,
        )
    }

    fun zoneForAngle(angleDegrees: Int): PostureZone = when {
        angleDegrees < SAFE_LIMIT_DEGREES -> PostureZone.SAFE
        angleDegrees < BAD_POSTURE_LIMIT_DEGREES -> PostureZone.WARNING
        else -> PostureZone.DANGER
    }

    private data class BodyLandmarks(
        val faceCenter: LandmarkPoint,
        val leftShoulder: LandmarkPoint,
        val rightShoulder: LandmarkPoint,
        val hipCenter: LandmarkPoint?,
        val confidence: Float,
    ) {
        companion object {
            fun from(points: List<LandmarkPoint>): BodyLandmarks? {
                val leftShoulder = points.tracked(LEFT_SHOULDER) ?: return null
                val rightShoulder = points.tracked(RIGHT_SHOULDER) ?: return null
                val nose = points.tracked(NOSE)
                val eyes = weightedCenter(
                    listOfNotNull(
                        points.tracked(LEFT_EYE_INNER)?.let { it to 1f },
                        points.tracked(LEFT_EYE)?.let { it to 1.25f },
                        points.tracked(LEFT_EYE_OUTER)?.let { it to 1f },
                        points.tracked(RIGHT_EYE_INNER)?.let { it to 1f },
                        points.tracked(RIGHT_EYE)?.let { it to 1.25f },
                        points.tracked(RIGHT_EYE_OUTER)?.let { it to 1f },
                    ),
                )
                val ears = weightedCenter(
                    listOfNotNull(
                        points.tracked(LEFT_EAR)?.let { it to 1f },
                        points.tracked(RIGHT_EAR)?.let { it to 1f },
                    ),
                )
                val mouth = weightedCenter(
                    listOfNotNull(
                        points.tracked(MOUTH_LEFT)?.let { it to 1f },
                        points.tracked(MOUTH_RIGHT)?.let { it to 1f },
                    ),
                )
                val faceParts = listOfNotNull(
                    nose?.let { it to 3f },
                    eyes?.let { it to 2.5f },
                    ears?.let { it to 1.5f },
                    mouth?.let { it to 1.5f },
                )
                val faceCenter = weightedCenter(faceParts) ?: return null
                val hips = weightedCenter(
                    listOfNotNull(
                        points.tracked(LEFT_HIP)?.let { it to 1f },
                        points.tracked(RIGHT_HIP)?.let { it to 1f },
                    ),
                )
                val confidencePoints = faceParts.map { it.first } + leftShoulder + rightShoulder
                val confidence = confidencePoints
                    .map { minOf(it.visibility, it.presence) }
                    .average()
                    .toFloat()

                return BodyLandmarks(faceCenter, leftShoulder, rightShoulder, hips, confidence)
            }
        }
    }

    private fun List<LandmarkPoint>.tracked(index: Int): LandmarkPoint? {
        val point = getOrNull(index) ?: return null
        return point.takeIf {
            it.visibility >= MIN_TRACKING_CONFIDENCE && it.presence >= MIN_TRACKING_CONFIDENCE
        }
    }

    private fun weightedCenter(points: List<Pair<LandmarkPoint, Float>>): LandmarkPoint? {
        if (points.isEmpty()) return null
        val totalWeight = points.sumOf { it.second.toDouble() }.toFloat()
        return LandmarkPoint(
            x = points.sumOf { (point, weight) -> (point.x * weight).toDouble() }.toFloat() / totalWeight,
            y = points.sumOf { (point, weight) -> (point.y * weight).toDouble() }.toFloat() / totalWeight,
            z = points.sumOf { (point, weight) -> (point.z * weight).toDouble() }.toFloat() / totalWeight,
            visibility = points.sumOf { (point, weight) -> (point.visibility * weight).toDouble() }.toFloat() / totalWeight,
            presence = points.sumOf { (point, weight) -> (point.presence * weight).toDouble() }.toFloat() / totalWeight,
        )
    }

    private fun midpoint(a: LandmarkPoint, b: LandmarkPoint): LandmarkPoint =
        weightedCenter(listOf(a to 1f, b to 1f)) ?: a

    private fun distance2d(a: LandmarkPoint, b: LandmarkPoint): Float =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

    private fun pairAngleDegrees(a: LandmarkPoint, b: LandmarkPoint): Float =
        Math.toDegrees(
            atan2(abs(b.y - a.y).toDouble(), abs(b.x - a.x).toDouble()),
        ).toFloat()
}
