package com.google.mediapipe.examples.poselandmarker

data class LandmarkPoint(
    val x: Float,
    val y: Float,
    val z: Float = 0f,
    val visibility: Float = 1f,
    val presence: Float = 1f,
)

enum class PostureZone {
    SAFE,
    WARNING,
    DANGER,
}

data class CalibrationProfile(
    val angleDegrees: Float,
    val postureRatio: Float,
    val shoulderWidth: Float,
    val calibratedAtMs: Long = System.currentTimeMillis(),
)

data class PostureMetrics(
    val angleDegrees: Int,
    val zone: PostureZone,
    val postureRatio: Float,
    val headTiltLabel: String,
    val neckCurvatureLabel: String,
    val shoulderBalanceDegrees: Int,
    val shoulderBalanceLabel: String,
    val rawAngleDegrees: Float = angleDegrees.toFloat(),
    val relativeAngleDegrees: Int = angleDegrees,
    val neckFlexionDegrees: Int = 0,
    val screenDistanceCm: Int? = null,
    val isTooClose: Boolean = false,
    val landmarkConfidence: Float = 0f,
    val shoulderWidth: Float = 0f,
    val deviceTiltDegrees: Int = 0,
    val isDeviceFlat: Boolean = false,
    val isRapidFall: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    val isGoodPosture: Boolean
        get() = zone == PostureZone.SAFE
}

data class HeadUpTask(
    val id: String,
    val reward: Int,
    val progress: Int,
    val max: Int,
    val claimed: Boolean = false,
) {
    val isComplete: Boolean
        get() = progress >= max
}

data class ShopItem(
    val id: String,
    val cost: Int,
    val isOwned: Boolean,
)

data class HeadUpUiState(
    val metrics: PostureMetrics = PostureAnalyzer.defaultMetrics(),
    val goodPostureSecondsToday: Long = 0L,
    val warningSecondsToday: Long = 0L,
    val dangerSecondsToday: Long = 0L,
    val eyeRestCountToday: Int = 0,
    val consecutiveDays: Int = 0,
    val dragonEnergy: Int = 50,
    val dragonLevel: Int = 1,
    val coins: Int = 0,
    val lastUpdatedMs: Long = 0L,
    val calibrationProfile: CalibrationProfile? = null,
    val ownedShopItems: Set<String> = emptySet(),
    val claimedTasks: Set<String> = emptySet(),
    val isAlarmEnabled: Boolean = false,
) {
    val goodPostureMinutesToday: Int
        get() = (goodPostureSecondsToday / 60L).toInt()

    val totalTrackedSecondsToday: Long
        get() = goodPostureSecondsToday + warningSecondsToday + dangerSecondsToday

    val protectEyesPercent: Int
        get() = if (totalTrackedSecondsToday == 0L) 0 else
            ((goodPostureSecondsToday * 100L) / totalTrackedSecondsToday).toInt().coerceIn(0, 100)

    val tasks: List<HeadUpTask>
        get() = listOf(
            HeadUpTask("good_posture", 50, goodPostureMinutesToday.coerceAtMost(30), 30, "good_posture" in claimedTasks),
            HeadUpTask("eye_rest", 50, eyeRestCountToday.coerceAtMost(3), 3, "eye_rest" in claimedTasks),
            HeadUpTask("posture_challenge", 100, if (protectEyesPercent >= 80 && totalTrackedSecondsToday >= 600L) 1 else 0, 1, "posture_challenge" in claimedTasks),
        )

    val shopItems: List<ShopItem>
        get() = listOf(
            ShopItem("starlight_armor", 300, "starlight_armor" in ownedShopItems),
            ShopItem("ocean_background", 180, "ocean_background" in ownedShopItems),
            ShopItem("eye_time_ticket", 500, "eye_time_ticket" in ownedShopItems),
            ShopItem("focus_badge", 220, "focus_badge" in ownedShopItems),
        )
}

data class DailyPostureSummary(
    val dayStartMs: Long,
    val safeSeconds: Long,
    val warningSeconds: Long,
    val dangerSeconds: Long,
    val dangerEvents: Int,
)

data class PostureDashboard(
    val today: DailyPostureSummary = DailyPostureSummary(0L, 0L, 0L, 0L, 0),
    val week: List<DailyPostureSummary> = emptyList(),
)
