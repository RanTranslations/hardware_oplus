/*
 * Copyright (C) 2021 The LineageOS Project
 * Copyright (C) 2022 FlamingoOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flamingo.device.alertslider

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.AudioManager
import android.media.AudioSystem
import android.os.UEventObserver
import android.os.UserHandle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
import android.provider.Settings.Global.ZEN_MODE_NO_INTERRUPTIONS
import android.provider.Settings.Global.ZEN_MODE_OFF
import android.util.Log

import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope

import java.io.File
import java.lang.Thread
import Java.util.concurrent.Executors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KeyHandler : LifecycleService() {

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val vibrator by lazy { getSystemService(Vibrator::class.java) }
    private val alertSliderController by lazy { AlertSliderController(this) }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val stream = intent?.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1)
            val state = intent?.getBooleanExtra(AudioManager.EXTRA_STREAM_VOLUME_MUTED, false)
            if (stream == AudioSystem.STREAM_MUSIC && state == false) {
                wasMuted = false
            }
        }
    }

    private val positionChangeChannel = Channel<AlertSliderPosition>(capacity = Channel.CONFLATED)

    private val alertSliderEventObserver = object : UEventObserver() {
        override fun onUEvent(event: UEvent) {
            event.get("SWITCH_STATE")?.let {
                lifecycleScope.launch {
                    positionChangeChannel.send(
                        when (it.toInt()) {
                            1 -> AlertSliderPosition.Top
                            2 -> AlertSliderPosition.Middle
                            3 -> AlertSliderPosition.Bottom
                            else -> return@launch
                        }
                    )
                }
            } ?: run {
                event.get("STATE")?.let {
                    handleState(it)
                }
            }
        }
    }

    private val executorService = Executors.newSingleThreadExecutor()
    private var wasMuted = false

    override fun onCreate() {
        super.onCreate()
        registerReceiver(
            broadcastReceiver,
            IntentFilter(AudioManager.STREAM_MUTE_CHANGED_ACTION)
        )
        lifecycleScope.launch(Dispatchers.IO) {
            for (position in positionChangeChannel) {
                handlePosition(position)
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            // Restore state
            File(SYSFS_EXTCON).walk().firstOrNull {
                it.isDirectory && it.name.matches("extcon\\d+".toRegex())
            }?.let {
                handleState(File(it, "state").readText(), restoring = true)
            }
            // Observe uevents
            alertSliderEventObserver.startObserving("tri-state-key")
            alertSliderEventObserver.startObserving("tri_state_key")
        }
    }

    private fun handleState(state: String, restoring: Boolean = false) {
        val none = state.contains("USB=0")
        val vibration = state.contains("HOST=0")
        val silent = state.contains("null)=0")
        val sliderPosition = when {
            none && !vibration && !silent -> AlertSliderPosition.Bottom
            vibration && !none && !silent -> AlertSliderPosition.Middle
            silent && !none && !vibration -> AlertSliderPosition.Top
            else -> return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            if (restoring) {
                handlePosition(sliderPosition, vibrate = false, showDialog = false)
            } else {
                positionChangeChannel.send(sliderPosition)
            }
        }
    }

    private suspend fun handlePosition(
        sliderPosition: AlertSliderPosition,
        vibrate: Boolean = true,
        showDialog: Boolean = true
    ) {
        val savedMode = Settings.System.getStringForUser(
            contentResolver,
            sliderPosition.modeKey,
            UserHandle.USER_CURRENT
        ) ?: sliderPosition.defaultMode.toString()
        val mode = try {
            Mode.valueOf(savedMode)
        } catch(_: IllegalArgumentException) {
            Log.e(TAG, "Unrecognised mode $savedMode")
            return
        }
        performSliderAction(mode, vibrate)
        if (showDialog) updateDialogAndShow(mode, sliderPosition)
    }

    private fun performSliderAction(mode: Mode, vibrate: Boolean) {
        val muteMedia = Settings.System.getIntForUser(
            contentResolver,
            MUTE_MEDIA_WITH_SILENT,
            0,
            UserHandle.USER_CURRENT
        ) == 1

        executorService.submit {
            when (mode) {
                Mode.NORMAL -> {
                    audioManager.ringerModeInternal = AudioManager.RINGER_MODE_NORMAL
                    setZenMode(ZEN_MODE_OFF)
                    if (vibrate) performHapticFeedback(HEAVY_CLICK_EFFECT)
                    if (muteMedia && wasMuted) {
                        audioManager.adjustVolume(AudioManager.ADJUST_UNMUTE, 0)
                    }
                }
                Mode.PRIORITY -> {
                    audioManager.ringerModeInternal = AudioManager.RINGER_MODE_NORMAL
                    setZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS)
                    if (vibrate) performHapticFeedback(HEAVY_CLICK_EFFECT)
                    if (muteMedia && wasMuted) {
                        audioManager.adjustVolume(AudioManager.ADJUST_UNMUTE, 0)
                    }
                }
                Mode.VIBRATE -> {
                    audioManager.ringerModeInternal = AudioManager.RINGER_MODE_VIBRATE
                    setZenMode(ZEN_MODE_OFF)
                    if (vibrate) performHapticFeedback(DOUBLE_CLICK_EFFECT)
                    if (muteMedia && wasMuted) {
                        audioManager.adjustVolume(AudioManager.ADJUST_UNMUTE, 0)
                    }
                }
                Mode.SILENT -> {
                    audioManager.ringerModeInternal = AudioManager.RINGER_MODE_SILENT
                    setZenMode(ZEN_MODE_OFF)
                    if (muteMedia) {
                        audioManager.adjustVolume(AudioManager.ADJUST_MUTE, 0)
                        wasMuted = true
                    }
                }
                Mode.DND -> {
                    audioManager.ringerModeInternal = AudioManager.RINGER_MODE_NORMAL
                    setZenMode(ZEN_MODE_NO_INTERRUPTIONS)
                }
            }
        }
    }

    private fun setZenMode(zenMode: Int) {
        // Set zen mode
        notificationManager.setZenMode(zenMode, null, TAG)

        // Wait until zen mode change is committed
        while (notificationManager.getZenMode() != zenMode) {
            Thread.sleep(10)
        }
    }

    private fun performHapticFeedback(effect: VibrationEffect) {
        if (vibrator.hasVibrator()) vibrator.vibrate(effect)
    }

    private suspend fun updateDialogAndShow(mode: Mode, position: AlertSliderPosition) {
        withContext(Dispatchers.Main) {
            alertSliderController.updateDialog(mode)
            alertSliderController.showDialog(position)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        alertSliderController.updateConfiguration(newConfig)
    }

    override fun onDestroy() {
        alertSliderEventObserver.stopObserving()
        unregisterReceiver(broadcastReceiver)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KeyHandler"

        // Vibration effects
        private val HEAVY_CLICK_EFFECT =
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        private val DOUBLE_CLICK_EFFECT =
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)

        private const val MUTE_MEDIA_WITH_SILENT = "config_mute_media"
        
        // Paths
        private const val SYSFS_EXTCON = "/sys/devices/platform/soc/soc:tri_state_key/extcon"
    }
}