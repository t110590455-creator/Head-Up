package com.google.mediapipe.examples.poselandmarker.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.mediapipe.examples.poselandmarker.HeadUpRepository
import com.google.mediapipe.examples.poselandmarker.HeadUpTask
import com.google.mediapipe.examples.poselandmarker.HeadUpUiState
import com.google.mediapipe.examples.poselandmarker.PostureDashboard
import com.google.mediapipe.examples.poselandmarker.PostureZone
import com.google.mediapipe.examples.poselandmarker.R
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentStatsBinding
import com.google.mediapipe.examples.poselandmarker.databinding.ItemDailyTaskBinding
import com.google.mediapipe.examples.poselandmarker.databinding.ItemStatCardBinding

class StatsFragment : Fragment() {
    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!
    private var latestState = HeadUpUiState()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        render(HeadUpRepository.currentState(requireContext()))
        HeadUpRepository.observeState().observe(viewLifecycleOwner) { render(it) }
        HeadUpRepository.observeDashboard().observe(viewLifecycleOwner) { renderDashboard(it) }
        HeadUpRepository.refreshDashboard(requireContext())
    }

    private fun render(state: HeadUpUiState) {
        latestState = state
        val zoneColor = ContextCompat.getColor(requireContext(), state.metrics.zone.colorRes())
        binding.currentAngleValue.text = "${state.metrics.angleDegrees}\u00B0"
        binding.currentAngleValue.setTextColor(zoneColor)
        binding.currentStatusText.text = getString(
            when (state.metrics.zone) {
                PostureZone.SAFE -> R.string.posture_status_safe
                PostureZone.WARNING -> R.string.posture_status_warning
                PostureZone.DANGER -> R.string.posture_status_danger
            },
        )
        binding.currentStatusText.setTextColor(zoneColor)

        bindStat(binding.cumulativeCard, "P", getString(R.string.cumulative_points), "%,d".format(state.coins))
        bindStat(binding.protectCard, "E", getString(R.string.protect_eyes), "${state.protectEyesPercent}%")
        bindStat(binding.goodPostureCard, "G", getString(R.string.good_posture_today), formatDuration(state.goodPostureSecondsToday))
        bindStat(binding.streakCard, "7", getString(R.string.streak_days), getString(R.string.days_format, state.consecutiveDays))

        val rows = listOf(binding.taskRowOne, binding.taskRowTwo, binding.taskRowThree)
        val titles = listOf(R.string.task_good_posture, R.string.task_eye_rest, R.string.task_posture_challenge)
        state.tasks.zip(rows).forEachIndexed { index, (task, row) ->
            bindTask(row, task, getString(titles[index]))
            row.root.setOnClickListener { handleTaskClick(task) }
        }
    }

    private fun renderDashboard(dashboard: PostureDashboard) {
        val today = dashboard.today
        val total = today.safeSeconds + today.warningSeconds + today.dangerSeconds
        val safePercent = if (total == 0L) 0 else (today.safeSeconds * 100L / total).toInt()
        val badPercent = if (total == 0L) 0 else ((today.warningSeconds + today.dangerSeconds) * 100L / total).toInt()
        binding.posturePieChart.setData(today.safeSeconds, today.warningSeconds, today.dangerSeconds)
        binding.postureLineChart.setData(dashboard.week.map { it.dangerEvents })
        binding.safePercentText.text = getString(R.string.safe_posture_percent, safePercent)
        binding.badPercentText.text = getString(R.string.bad_posture_percent, badPercent)
        binding.trackedTimeText.text = getString(R.string.tracked_time, formatDuration(total))
    }

    private fun handleTaskClick(task: HeadUpTask) {
        if (task.isComplete) {
            val claimed = HeadUpRepository.claimTask(requireContext(), task.id)
            Toast.makeText(
                requireContext(),
                if (claimed) R.string.reward_claimed else R.string.reward_already_claimed,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        when (task.id) {
            "eye_rest" -> showEyeRestDialog()
            "posture_challenge" -> findNavController().navigate(R.id.challenge_fragment)
            else -> findNavController().navigate(R.id.camera_fragment)
        }
    }

    private fun showEyeRestDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.eye_rest_title)
            .setMessage(R.string.eye_rest_message)
            .setPositiveButton(R.string.eye_rest_complete) { _, _ ->
                HeadUpRepository.recordEyeRest(requireContext())
                Toast.makeText(requireContext(), R.string.eye_rest_recorded, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun bindStat(card: ItemStatCardBinding, icon: String, label: String, value: String) {
        card.statIcon.text = icon
        card.statLabel.text = label
        card.statValue.text = value
    }

    private fun bindTask(row: ItemDailyTaskBinding, task: HeadUpTask, title: String) {
        row.taskTitle.text = title
        row.taskDetail.text = when (task.id) {
            "good_posture" -> getString(R.string.minutes_progress_format, task.progress, task.max)
            "eye_rest" -> getString(R.string.times_format, task.progress, task.max)
            else -> getString(R.string.challenge_progress_format, task.progress, task.max)
        }
        row.taskReward.text = if (task.claimed) getString(R.string.claimed) else "+${task.reward}"
        row.taskCheck.alpha = if (task.isComplete) 1f else 0.35f
        row.root.alpha = if (task.claimed) 0.65f else 1f
    }

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3_600L
        val minutes = (seconds % 3_600L) / 60L
        return if (hours > 0L) getString(R.string.hours_minutes_format, hours, minutes)
        else getString(R.string.minutes_only_format, minutes)
    }

    private fun PostureZone.colorRes(): Int = when (this) {
        PostureZone.SAFE -> R.color.headup_safe
        PostureZone.WARNING -> R.color.headup_warning
        PostureZone.DANGER -> R.color.headup_danger
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
