package com.google.mediapipe.examples.poselandmarker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.tan

class PostureAnalyzerTest {
    @Before
    fun resetFilter() {
        PostureAnalyzer.resetSmoothing()
    }

    @Test
    fun analyze_returnsSafeZoneForUprightPose() {
        val metrics = PostureAnalyzer.analyze(samplePose(rawAngle = 5f))

        assertEquals(PostureZone.SAFE, metrics?.zone)
        assertEquals(5, metrics?.angleDegrees)
        assertEquals("平衡", metrics?.shoulderBalanceLabel)
    }

    @Test
    fun analyze_treatsTwentyFiveDegreesAsBadPosture() {
        val metrics = PostureAnalyzer.analyze(samplePose(rawAngle = 25f))

        assertEquals(25, metrics?.angleDegrees)
        assertEquals(PostureZone.DANGER, metrics?.zone)
        assertEquals("姿勢不良", metrics?.neckCurvatureLabel)
    }

    @Test
    fun emaTrustsOnlyFifteenPercentOfNewFrame() {
        PostureAnalyzer.analyze(samplePose(rawAngle = 0f))
        val metrics = PostureAnalyzer.analyze(samplePose(rawAngle = 60f))

        assertEquals(9, metrics?.angleDegrees)
        assertEquals(PostureZone.SAFE, metrics?.zone)
    }

    @Test
    fun rapidFallIsIndependentFromDangerThreshold() {
        PostureAnalyzer.analyze(samplePose(rawAngle = 20f))
        val metrics = PostureAnalyzer.analyze(samplePose(rawAngle = 40f))

        assertEquals(23, metrics?.angleDegrees)
        assertEquals(PostureZone.WARNING, metrics?.zone)
        assertTrue(metrics?.isRapidFall == true)
    }

    @Test
    fun slowMovementDoesNotTriggerRapidFall() {
        PostureAnalyzer.analyze(samplePose(rawAngle = 22f))
        val metrics = PostureAnalyzer.analyze(samplePose(rawAngle = 23f))

        assertFalse(metrics?.isRapidFall == true)
    }

    @Test
    fun analyze_detectsShoulderImbalance() {
        val metrics = PostureAnalyzer.analyze(
            samplePose(rawAngle = 5f, leftShoulderY = 0.76f, rightShoulderY = 0.88f),
        )

        assertEquals("左右不平衡", metrics?.shoulderBalanceLabel)
    }

    @Test
    fun analyze_usesEyesEarsAndMouthWhenNoseConfidenceIsLow() {
        val points = samplePose(rawAngle = 5f).toMutableList().apply {
            this[0] = this[0].copy(visibility = 0f, presence = 0f)
        }

        assertNotNull(PostureAnalyzer.analyze(points))
    }

    @Test
    fun analyze_returnsNullWhenLandmarksAreMissing() {
        assertNull(PostureAnalyzer.analyze(emptyList()))
    }

    @Test
    fun calibrationMakesUprightPoseThePersonalZero() {
        val calibration = CalibrationProfile(
            angleDegrees = 18f,
            postureRatio = 0.8f,
            shoulderWidth = 0.6f,
        )

        val metrics = PostureAnalyzer.analyze(samplePose(rawAngle = 18f), calibration = calibration)

        assertEquals(0, metrics?.relativeAngleDegrees)
        assertEquals(PostureZone.SAFE, metrics?.zone)
    }

    @Test
    fun calibratedScreenDistanceDetectsMovingTooClose() {
        val calibration = CalibrationProfile(
            angleDegrees = 0f,
            postureRatio = 0.8f,
            shoulderWidth = 0.3f,
        )

        val metrics = PostureAnalyzer.analyze(samplePose(rawAngle = 5f), calibration = calibration)

        assertTrue(metrics?.isTooClose == true)
        assertEquals(PostureZone.DANGER, metrics?.zone)
    }

    @Test
    fun neckCompressionCanRaisePostureRisk() {
        val calibration = CalibrationProfile(
            angleDegrees = 0f,
            postureRatio = 0.82f,
            shoulderWidth = 0.6f,
        )

        val metrics = PostureAnalyzer.analyze(
            samplePose(rawAngle = 5f, faceY = 0.55f),
            calibration = calibration,
        )

        assertTrue((metrics?.neckFlexionDegrees ?: 0) >= 25)
        assertEquals(PostureZone.DANGER, metrics?.zone)
    }

    private fun samplePose(
        rawAngle: Float,
        faceY: Float = 0.32f,
        leftShoulderY: Float = 0.80f,
        rightShoulderY: Float = 0.80f,
    ): List<LandmarkPoint> {
        val shoulderCenterY = (leftShoulderY + rightShoulderY) / 2f
        val faceZ = -tan(Math.toRadians(rawAngle.toDouble())).toFloat() * (shoulderCenterY - faceY)
        fun facePoint(x: Float, y: Float) = LandmarkPoint(x, y, z = faceZ)

        return MutableList(33) {
            LandmarkPoint(0.5f, 0.5f, visibility = 0f, presence = 0f)
        }.apply {
            this[0] = facePoint(0.50f, faceY)
            this[1] = facePoint(0.42f, faceY - 0.03f)
            this[2] = facePoint(0.40f, faceY - 0.03f)
            this[3] = facePoint(0.38f, faceY - 0.03f)
            this[4] = facePoint(0.58f, faceY - 0.03f)
            this[5] = facePoint(0.60f, faceY - 0.03f)
            this[6] = facePoint(0.62f, faceY - 0.03f)
            this[7] = facePoint(0.34f, faceY - 0.02f)
            this[8] = facePoint(0.66f, faceY - 0.02f)
            this[9] = facePoint(0.45f, faceY + 0.06f)
            this[10] = facePoint(0.55f, faceY + 0.06f)
            this[11] = LandmarkPoint(0.2f, leftShoulderY, z = 0f)
            this[12] = LandmarkPoint(0.8f, rightShoulderY, z = 0f)
            this[23] = LandmarkPoint(0.30f, 1.20f, z = 0f)
            this[24] = LandmarkPoint(0.70f, 1.20f, z = 0f)
        }
    }
}
