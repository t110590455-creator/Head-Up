package com.google.mediapipe.examples.poselandmarker.fragment

import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.mediapipe.examples.poselandmarker.HeadUpRepository
import com.google.mediapipe.examples.poselandmarker.HeadUpUiState
import com.google.mediapipe.examples.poselandmarker.PostureZone
import com.google.mediapipe.examples.poselandmarker.R
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentChallengeBinding

class ChallengeFragment : Fragment() {
    private var _binding: FragmentChallengeBinding? = null
    private val binding get() = _binding!!
    private var mediaPlayer: MediaPlayer? = null
    private var currentSurface: Surface? = null
    private var currentVideoResId = 0
    private var latestState = HeadUpUiState()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentChallengeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupDragonOrb()
        binding.dragonVideoView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                currentSurface = Surface(texture)
                updateDragonVideo(currentVideoResId.takeIf { it != 0 } ?: R.raw.blue_dragon)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                releaseMediaPlayer()
                currentSurface?.release()
                currentSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        binding.dragonVideoView.setOnClickListener {
            mediaPlayer?.let { player -> if (player.isPlaying) player.pause() else player.start() }
        }
        binding.claimMaintainButton.setOnClickListener { claimGoodPostureReward() }
        binding.recordEyeRestButton.setOnClickListener { showEyeRestDialog() }

        render(HeadUpRepository.currentState(requireContext()))
        HeadUpRepository.observeState().observe(viewLifecycleOwner) { render(it) }
    }

    override fun onResume() {
        super.onResume()
        if (currentSurface != null && currentVideoResId != 0) updateDragonVideo(currentVideoResId)
    }

    override fun onPause() {
        releaseMediaPlayer()
        super.onPause()
    }

    private fun setupDragonOrb() {
        binding.dragonVideoView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        binding.dragonVideoView.clipToOutline = true
    }

    private fun render(state: HeadUpUiState) {
        if (_binding == null) return
        latestState = state
        val zoneColor = ContextCompat.getColor(requireContext(), state.metrics.zone.colorRes())
        binding.dragonLevelBadge.text = "Lv.${state.dragonLevel}"
        binding.dragonEnergyProgress.progress = state.dragonEnergy
        binding.dragonEnergyText.text = getString(
            if (state.metrics.isGoodPosture) R.string.dragon_energy_good else R.string.dragon_energy_rest,
            state.dragonEnergy,
        )
        binding.dragonMoodText.setText(
            when (state.metrics.zone) {
                PostureZone.SAFE -> R.string.dragon_mood_safe
                PostureZone.WARNING -> R.string.dragon_mood_warning
                PostureZone.DANGER -> R.string.dragon_mood_danger
            },
        )
        binding.dragonMoodText.setTextColor(zoneColor)
        
        // 亮起紅框：當姿勢為 DANGER 時顯示紅框
        if (state.metrics.zone == PostureZone.DANGER) {
            binding.dragonOrb.setBackgroundResource(R.drawable.bg_headup_dragon_orb_danger)
        } else {
            binding.dragonOrb.setBackgroundResource(R.drawable.bg_headup_dragon_orb)
        }

        binding.maintainTaskDetail.text = getString(
            R.string.minutes_progress_format,
            state.goodPostureMinutesToday.coerceAtMost(30),
            30,
        )
        binding.protectTaskProgress.progress = state.eyeRestCountToday.coerceAtMost(3)
        binding.protectTaskDetail.text = getString(R.string.times_format, state.eyeRestCountToday.coerceAtMost(3), 3)

        val goodTask = state.tasks.first { it.id == "good_posture" }
        binding.claimMaintainButton.isEnabled = goodTask.isComplete && !goodTask.claimed
        binding.claimMaintainButton.setText(if (goodTask.claimed) R.string.claimed else R.string.claim_reward)
        updateDragonVideo(if (state.metrics.zone == PostureZone.DANGER) R.raw.angry_dragon else R.raw.blue_dragon)
    }

    private fun claimGoodPostureReward() {
        val claimed = HeadUpRepository.claimTask(requireContext(), "good_posture")
        Toast.makeText(
            requireContext(),
            if (claimed) R.string.reward_claimed else R.string.task_not_complete,
            Toast.LENGTH_SHORT,
        ).show()
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

    private fun updateDragonVideo(videoResId: Int) {
        if (currentVideoResId == videoResId && mediaPlayer?.isPlaying == true) return
        currentVideoResId = videoResId
        val surface = currentSurface ?: return
        releaseMediaPlayer()
        try {
            val descriptor = resources.openRawResourceFd(videoResId)
            mediaPlayer = MediaPlayer().apply {
                setSurface(surface)
                setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                isLooping = true
                setVolume(0f, 0f)
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
            descriptor.close()
        } catch (error: Exception) {
            Log.e("ChallengeFragment", "Unable to play dragon animation", error)
        }
    }

    private fun releaseMediaPlayer() {
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
            Unit
        }
        mediaPlayer = null
    }

    private fun PostureZone.colorRes(): Int = when (this) {
        PostureZone.SAFE -> R.color.headup_safe
        PostureZone.WARNING -> R.color.headup_warning
        PostureZone.DANGER -> R.color.headup_danger
    }

    override fun onDestroyView() {
        releaseMediaPlayer()
        _binding = null
        super.onDestroyView()
    }
}
