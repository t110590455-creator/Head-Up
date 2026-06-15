/*
 * Copyright 2023 The TensorFlow Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.mediapipe.examples.poselandmarker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.mediapipe.examples.poselandmarker.databinding.ActivityMainBinding
import com.google.mediapipe.examples.poselandmarker.fragment.PermissionsFragment

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Toast.makeText(
                this,
                if (granted) R.string.notification_permission_granted else R.string.notification_permission_denied,
                Toast.LENGTH_SHORT,
            ).show()
            checkAndPromptOverlayPermission()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        playLaunchAnimation()

        val navHost = supportFragmentManager
            .findFragmentById(R.id.fragment_container) as NavHostFragment
        binding.navigation.setupWithNavController(navHost.navController)
        binding.navigation.setOnItemReselectedListener { }

        binding.notificationButton.setOnClickListener { openNotificationControls() }
        binding.settingsButton.setOnClickListener { showSettingsDialog() }
        initPermissionFlow()
    }

    fun startHeadUpService(action: String = HeadUpService.ACTION_PAUSE_CAMERA) {
        if (!PermissionsFragment.hasPermissions(this)) return
        ContextCompat.startForegroundService(
            this,
            Intent(this, HeadUpService::class.java).setAction(action),
        )
    }

    private fun playLaunchAnimation() {
        binding.splashLogo.apply {
            alpha = 0f
            scaleX = 0.84f
            scaleY = 0.84f
            translationY = 24f
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(650L)
                .start()
        }
        binding.splashStatus.apply {
            alpha = 0f
            animate().alpha(1f).setStartDelay(280L).setDuration(400L).start()
        }
        binding.splashOverlay.postDelayed({
            binding.splashOverlay.animate()
                .alpha(0f)
                .setDuration(420L)
                .withEndAction { binding.splashOverlay.visibility = View.GONE }
                .start()
        }, 1_250L)
    }

    private fun showSettingsDialog() {
        val items = arrayOf(
            getString(R.string.settings_language),
            getString(R.string.settings_overlay),
            getString(R.string.settings_calibration),
            getString(R.string.settings_reset_data),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setItems(items) { _, index ->
                when (index) {
                    0 -> showLanguagePicker()
                    1 -> openOverlaySettingsIfNeeded()
                    2 -> navigateToCalibration()
                    3 -> confirmResetData()
                }
            }
            .show()
    }

    private fun navigateToCalibration() {
        HeadUpRepository.requestCalibration(this)
        binding.navigation.selectedItemId = R.id.camera_fragment
        Toast.makeText(this, R.string.calibration_prepare, Toast.LENGTH_LONG).show()
    }

    private fun confirmResetData() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_data_title)
            .setMessage(R.string.reset_data_message)
            .setPositiveButton(R.string.reset_data_confirm) { _, _ ->
                HeadUpRepository.resetAllData(this)
                Toast.makeText(this, R.string.reset_data_complete, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showLanguagePicker() {
        val languageTags = arrayOf("en", "zh-TW")
        val labels = arrayOf(
            getString(R.string.language_english),
            getString(R.string.language_chinese),
        )
        val currentTag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val selectedIndex = if (currentTag.startsWith("zh")) 1 else 0
        AlertDialog.Builder(this)
            .setTitle(R.string.language_title)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(languageTags[which]),
                )
                dialog.dismiss()
            }
            .show()
    }

    private fun initPermissionFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermissionIfNeeded()
        } else {
            checkAndPromptOverlayPermission()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            checkAndPromptOverlayPermission()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.notification_permission_granted, Toast.LENGTH_SHORT).show()
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openNotificationControls() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            },
        )
    }

    private fun checkAndPromptOverlayPermission() {
        if (Settings.canDrawOverlays(this)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_permission_title)
            .setMessage(R.string.overlay_permission_message)
            .setPositiveButton(R.string.overlay_permission_open) { _, _ -> openOverlaySettingsIfNeeded() }
            .setNegativeButton(R.string.overlay_permission_later, null)
            .show()
    }

    private fun openOverlaySettingsIfNeeded() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_permission_enabled, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finish()
    }
}
