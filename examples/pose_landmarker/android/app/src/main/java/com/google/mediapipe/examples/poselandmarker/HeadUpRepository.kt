package com.google.mediapipe.examples.poselandmarker

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.math.roundToLong

object HeadUpRepository {
    private const val PREFS_NAME = "headup_state"
    private const val KEY_GOOD_SECONDS = "good_seconds"
    private const val KEY_WARNING_SECONDS = "warning_seconds"
    private const val KEY_DANGER_SECONDS = "danger_seconds"
    private const val KEY_EYE_REST_COUNT = "eye_rest_count"
    private const val KEY_CONSECUTIVE_DAYS = "consecutive_days"
    private const val KEY_DRAGON_ENERGY = "dragon_energy"
    private const val KEY_DRAGON_LEVEL = "dragon_level"
    private const val KEY_COINS = "coins"
    private const val KEY_LAST_UPDATED = "last_updated"
    private const val KEY_STATE_DAY = "state_day"
    private const val KEY_CALIBRATION_ANGLE = "calibration_angle"
    private const val KEY_CALIBRATION_RATIO = "calibration_ratio"
    private const val KEY_CALIBRATION_SHOULDER = "calibration_shoulder"
    private const val KEY_CALIBRATION_TIME = "calibration_time"
    private const val KEY_FOREGROUND_SCAN_ACTIVE = "foreground_scan_active"
    private const val KEY_OWNED_ITEMS = "owned_items"
    private const val KEY_CLAIMED_TASKS = "claimed_tasks"
    private const val KEY_ALARM_ENABLED = "alarm_enabled"
    private const val KEY_CALIBRATION_REQUESTED = "calibration_requested"
    private const val RECORD_INTERVAL_MS = 1_000L
    private const val MAX_RECORD_INTERVAL_MS = 2_000L
    private const val HISTORY_RETENTION_DAYS = 90

    private val stateLiveData = MutableLiveData(HeadUpUiState())
    private val dashboardLiveData = MutableLiveData(PostureDashboard())
    private val databaseExecutor = Executors.newSingleThreadExecutor()
    private var lastDashboardRefreshMs = 0L

    fun observeState(): LiveData<HeadUpUiState> = stateLiveData

    fun observeDashboard(): LiveData<PostureDashboard> = dashboardLiveData

    @Synchronized
    fun currentState(context: Context): HeadUpUiState =
        loadState(context).also { stateLiveData.postValue(it) }

    fun setCalibration(context: Context, profile: CalibrationProfile) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putFloat(KEY_CALIBRATION_ANGLE, profile.angleDegrees)
            putFloat(KEY_CALIBRATION_RATIO, profile.postureRatio)
            putFloat(KEY_CALIBRATION_SHOULDER, profile.shoulderWidth)
            putLong(KEY_CALIBRATION_TIME, profile.calibratedAtMs)
        }
        PostureAnalyzer.resetSmoothing()
        currentState(context)
    }

    fun getCalibration(context: Context): CalibrationProfile? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_CALIBRATION_ANGLE)) return null
        return CalibrationProfile(
            angleDegrees = prefs.getFloat(KEY_CALIBRATION_ANGLE, 0f),
            postureRatio = prefs.getFloat(KEY_CALIBRATION_RATIO, 0f),
            shoulderWidth = prefs.getFloat(KEY_CALIBRATION_SHOULDER, 0f),
            calibratedAtMs = prefs.getLong(KEY_CALIBRATION_TIME, 0L),
        )
    }

    fun clearCalibration(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            remove(KEY_CALIBRATION_ANGLE)
            remove(KEY_CALIBRATION_RATIO)
            remove(KEY_CALIBRATION_SHOULDER)
            remove(KEY_CALIBRATION_TIME)
        }
        PostureAnalyzer.resetSmoothing()
        currentState(context)
    }

    fun requestCalibration(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_CALIBRATION_REQUESTED, true) }
    }

    fun consumeCalibrationRequest(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val requested = prefs.getBoolean(KEY_CALIBRATION_REQUESTED, false)
        if (requested) prefs.edit { putBoolean(KEY_CALIBRATION_REQUESTED, false) }
        return requested
    }

    @Synchronized
    fun recordMetrics(
        context: Context,
        metrics: PostureMetrics,
        source: String,
    ): HeadUpUiState {
        val appContext = context.applicationContext
        val previous = loadState(appContext)
        val now = metrics.timestampMs
        val elapsedMs = (now - previous.lastUpdatedMs).coerceIn(0L, MAX_RECORD_INTERVAL_MS)
        if (previous.lastUpdatedMs > 0L && elapsedMs < RECORD_INTERVAL_MS) {
            return previous.copy(metrics = metrics).also { stateLiveData.postValue(it) }
        }

        val elapsedSeconds = (elapsedMs / 1_000f).roundToLong()
        val goodSeconds = previous.goodPostureSecondsToday + if (metrics.zone == PostureZone.SAFE) elapsedSeconds else 0L
        val warningSeconds = previous.warningSecondsToday + if (metrics.zone == PostureZone.WARNING) elapsedSeconds else 0L
        val dangerSeconds = previous.dangerSecondsToday + if (metrics.zone == PostureZone.DANGER) elapsedSeconds else 0L
        val energyDelta = when (metrics.zone) {
            PostureZone.SAFE -> elapsedSeconds.toInt()
            PostureZone.WARNING -> 0
            PostureZone.DANGER -> -elapsedSeconds.toInt()
        }
        val energy = (previous.dragonEnergy + energyDelta).coerceIn(0, 100)
        val level = previous.dragonLevel + if (energy == 100 && previous.dragonEnergy < 100) 1 else 0
        val next = previous.copy(
            metrics = metrics,
            goodPostureSecondsToday = goodSeconds,
            warningSecondsToday = warningSeconds,
            dangerSecondsToday = dangerSeconds,
            dragonEnergy = energy,
            dragonLevel = level,
            lastUpdatedMs = now,
        )
        saveState(appContext, next)
        stateLiveData.postValue(next)

        if (elapsedMs > 0L) {
            val record = PostureRecordEntity(
                timestampMs = now,
                durationMs = elapsedMs,
                angleDegrees = metrics.angleDegrees,
                rawAngleDegrees = metrics.rawAngleDegrees,
                neckFlexionDegrees = metrics.neckFlexionDegrees,
                shoulderBalanceDegrees = metrics.shoulderBalanceDegrees,
                screenDistanceCm = metrics.screenDistanceCm,
                landmarkConfidence = metrics.landmarkConfidence,
                zone = metrics.zone.name,
                source = source,
            )
            databaseExecutor.execute {
                val dao = PostureDatabase.getInstance(appContext).postureRecordDao()
                dao.insert(record)
                if (now - lastDashboardRefreshMs >= 5_000L) {
                    lastDashboardRefreshMs = now
                    refreshDashboardInternal(appContext, dao)
                }
            }
        }
        return next
    }

    fun recordEyeRest(context: Context) {
        val state = loadState(context)
        val next = state.copy(eyeRestCountToday = state.eyeRestCountToday + 1)
        saveState(context, next)
        stateLiveData.postValue(next)
    }

    fun claimTask(context: Context, taskId: String): Boolean {
        val state = loadState(context)
        val task = state.tasks.firstOrNull { it.id == taskId } ?: return false
        if (!task.isComplete || task.claimed) return false
        val next = state.copy(
            coins = state.coins + task.reward,
            claimedTasks = state.claimedTasks + taskId,
        )
        saveState(context, next)
        stateLiveData.postValue(next)
        return true
    }

    fun purchaseItem(context: Context, itemId: String): Boolean {
        val state = loadState(context)
        val item = state.shopItems.firstOrNull { it.id == itemId } ?: return false
        if (item.isOwned || state.coins < item.cost) return false
        val next = state.copy(
            coins = state.coins - item.cost,
            ownedShopItems = state.ownedShopItems + itemId,
        )
        saveState(context, next)
        stateLiveData.postValue(next)
        return true
    }

    fun refreshDashboard(context: Context) {
        val appContext = context.applicationContext
        databaseExecutor.execute {
            refreshDashboardInternal(appContext, PostureDatabase.getInstance(appContext).postureRecordDao())
        }
    }

    fun resetAllData(context: Context) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { clear() }
        stateLiveData.postValue(HeadUpUiState())
        dashboardLiveData.postValue(PostureDashboard())
        databaseExecutor.execute {
            PostureDatabase.getInstance(appContext).postureRecordDao().deleteAll()
        }
        PostureAnalyzer.resetSmoothing()
    }

    fun setForegroundScanActive(context: Context, active: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_FOREGROUND_SCAN_ACTIVE, active) }
    }

    fun isForegroundScanActive(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FOREGROUND_SCAN_ACTIVE, false)

    fun isAlarmEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ALARM_ENABLED, false)

    fun setAlarmEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_ALARM_ENABLED, enabled) }
        currentState(context) // Trigger UI update
    }

    private fun refreshDashboardInternal(context: Context, dao: PostureRecordDao) {
        val todayStart = startOfDay(System.currentTimeMillis())
        val firstDay = addDays(todayStart, -6)
        val records = dao.recordsBetween(firstDay, addDays(todayStart, 1))
        val summaries = (0..6).map { index ->
            val dayStart = addDays(firstDay, index)
            val dayEnd = addDays(dayStart, 1)
            summarizeDay(dayStart, records.filter { it.timestampMs in dayStart until dayEnd })
        }
        dashboardLiveData.postValue(
            PostureDashboard(
                today = summaries.lastOrNull() ?: DailyPostureSummary(todayStart, 0L, 0L, 0L, 0),
                week = summaries,
            ),
        )
        dao.deleteOlderThan(addDays(todayStart, -HISTORY_RETENTION_DAYS))

        var trackedDays = 0
        for (summary in summaries.asReversed()) {
            if (summary.safeSeconds + summary.warningSeconds + summary.dangerSeconds <= 0L) break
            trackedDays++
        }
        val state = loadState(context)
        if (state.consecutiveDays != trackedDays) {
            val next = state.copy(consecutiveDays = trackedDays)
            saveState(context, next)
            stateLiveData.postValue(next)
        }
    }

    private fun summarizeDay(dayStart: Long, records: List<PostureRecordEntity>): DailyPostureSummary {
        var previousDanger = false
        var dangerEvents = 0
        records.forEach { record ->
            val isDanger = record.zone == PostureZone.DANGER.name
            if (isDanger && !previousDanger) dangerEvents++
            previousDanger = isDanger
        }
        fun secondsFor(zone: PostureZone): Long = records
            .filter { it.zone == zone.name }
            .sumOf { it.durationMs } / 1_000L
        return DailyPostureSummary(
            dayStartMs = dayStart,
            safeSeconds = secondsFor(PostureZone.SAFE),
            warningSeconds = secondsFor(PostureZone.WARNING),
            dangerSeconds = secondsFor(PostureZone.DANGER),
            dangerEvents = dangerEvents,
        )
    }

    private fun loadState(context: Context): HeadUpUiState {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = startOfDay(System.currentTimeMillis())
        val storedDay = prefs.getLong(KEY_STATE_DAY, today)
        val isToday = storedDay == today
        return HeadUpUiState(
            metrics = stateLiveData.value?.metrics ?: PostureAnalyzer.defaultMetrics(),
            goodPostureSecondsToday = if (isToday) prefs.getLong(KEY_GOOD_SECONDS, 0L) else 0L,
            warningSecondsToday = if (isToday) prefs.getLong(KEY_WARNING_SECONDS, 0L) else 0L,
            dangerSecondsToday = if (isToday) prefs.getLong(KEY_DANGER_SECONDS, 0L) else 0L,
            eyeRestCountToday = if (isToday) prefs.getInt(KEY_EYE_REST_COUNT, 0) else 0,
            consecutiveDays = prefs.getInt(KEY_CONSECUTIVE_DAYS, 0),
            dragonEnergy = prefs.getInt(KEY_DRAGON_ENERGY, 50),
            dragonLevel = prefs.getInt(KEY_DRAGON_LEVEL, 1),
            coins = prefs.getInt(KEY_COINS, 0),
            lastUpdatedMs = if (isToday) prefs.getLong(KEY_LAST_UPDATED, 0L) else 0L,
            calibrationProfile = getCalibration(context),
            ownedShopItems = prefs.getStringSet(KEY_OWNED_ITEMS, emptySet())?.toSet().orEmpty(),
            claimedTasks = if (isToday) prefs.getStringSet(KEY_CLAIMED_TASKS, emptySet())?.toSet().orEmpty() else emptySet(),
            isAlarmEnabled = prefs.getBoolean(KEY_ALARM_ENABLED, false),
        )
    }

    private fun saveState(context: Context, state: HeadUpUiState) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putLong(KEY_STATE_DAY, startOfDay(System.currentTimeMillis()))
            putLong(KEY_GOOD_SECONDS, state.goodPostureSecondsToday)
            putLong(KEY_WARNING_SECONDS, state.warningSecondsToday)
            putLong(KEY_DANGER_SECONDS, state.dangerSecondsToday)
            putInt(KEY_EYE_REST_COUNT, state.eyeRestCountToday)
            putInt(KEY_CONSECUTIVE_DAYS, state.consecutiveDays)
            putInt(KEY_DRAGON_ENERGY, state.dragonEnergy)
            putInt(KEY_DRAGON_LEVEL, state.dragonLevel)
            putInt(KEY_COINS, state.coins)
            putLong(KEY_LAST_UPDATED, state.lastUpdatedMs)
            putStringSet(KEY_OWNED_ITEMS, state.ownedShopItems)
            putStringSet(KEY_CLAIMED_TASKS, state.claimedTasks)
        }
    }

    private fun startOfDay(timeMs: Long): Long = Calendar.getInstance().run {
        timeInMillis = timeMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    private fun addDays(timeMs: Long, days: Int): Long = Calendar.getInstance().run {
        timeInMillis = timeMs
        add(Calendar.DAY_OF_YEAR, days)
        timeInMillis
    }
}
