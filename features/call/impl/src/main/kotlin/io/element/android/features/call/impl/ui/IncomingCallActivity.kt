/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.ui

import android.app.KeyguardManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.IntentCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import dev.zacsweers.metro.Inject
import io.element.android.compound.colors.SemanticColorsLightDark
import io.element.android.features.call.api.CallData
import io.element.android.features.call.api.ElementCallEntryPoint
import io.element.android.features.call.impl.di.CallBindings
import io.element.android.features.call.impl.notifications.CallNotificationData
import io.element.android.features.call.impl.utils.ActiveCallManager
import io.element.android.features.call.impl.utils.CallState
import io.element.android.features.enterprise.api.EnterpriseService
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.designsystem.theme.ElementThemeApp
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Activity that's displayed as a full screen intent when an incoming call is received.
 */
class IncomingCallActivity : AppCompatActivity() {
    companion object {
        /**
         * Extra key for the notification data.
         */
        const val EXTRA_NOTIFICATION_DATA = "EXTRA_NOTIFICATION_DATA"

        // Set when the manager launches this activity directly (app in foreground) so
        // it plays the ringtone itself. Left false when the notification's full-screen
        // intent launches it (locked/background), since the notification rings then -
        // this avoids a double ringtone.
        const val EXTRA_PLAY_RINGTONE = "EXTRA_PLAY_RINGTONE"
    }

    private var ringtonePlayer: MediaPlayer? = null

    // When the AnswerCallOnLockScreen flag is on, answering does not dismiss the keyguard,
    // so the call opens over the lock screen without forcing an unlock.
    private var answerWithoutUnlocking = false

    @Inject
    lateinit var elementCallEntryPoint: ElementCallEntryPoint

    @Inject
    lateinit var activeCallManager: ActiveCallManager

    @Inject
    lateinit var appPreferencesStore: AppPreferencesStore

    @Inject
    lateinit var featureFlagService: FeatureFlagService

    @Inject
    lateinit var enterpriseService: EnterpriseService

    @Inject
    lateinit var buildMeta: BuildMeta

    @AppCoroutineScope
    @Inject lateinit var appCoroutineScope: CoroutineScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bindings<CallBindings>().inject(this)

        featureFlagService.isFeatureEnabledFlow(FeatureFlags.AnswerCallOnLockScreen)
            .onEach { answerWithoutUnlocking = it }
            .launchIn(lifecycleScope)

        // Set flags so it can be displayed in the lock screen
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        if (intent?.getBooleanExtra(EXTRA_PLAY_RINGTONE, false) == true) {
            startRingtone()
        }

        val notificationData = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_NOTIFICATION_DATA, CallNotificationData::class.java) }
        if (notificationData != null) {
            setContent {
                val colors by remember {
                    enterpriseService.semanticColorsFlow(sessionId = notificationData.sessionId)
                }.collectAsState(SemanticColorsLightDark.default)
                val phoneStyleIncomingCall by featureFlagService
                    .isFeatureEnabledFlow(FeatureFlags.PhoneIncomingCall)
                    .collectAsState(initial = true)
                ElementThemeApp(
                    appPreferencesStore = appPreferencesStore,
                    featureFlagService = featureFlagService,
                    compoundLight = colors.light,
                    compoundDark = colors.dark,
                    buildMeta = buildMeta,
                ) {
                    IncomingCallScreen(
                        notificationData = notificationData,
                        onAnswer = ::onAnswer,
                        onCancel = ::onCancel,
                        onAnswerWithoutCamera = ::onAnswerWithoutCamera,
                        phoneStyleIncomingCall = phoneStyleIncomingCall,
                    )
                }
            }
        } else {
            // No data, finish the activity
            finish()
            return
        }

        activeCallManager.activeCall
            .filter { it?.callState !is CallState.Ringing }
            .onEach { finish() }
            .launchIn(lifecycleScope)
    }

    private fun onAnswer(notificationData: CallNotificationData) {
        answerCall(notificationData, startVideoMuted = false)
    }

    // Answer a video call with the camera initially off (Telegram-style): join
    // the call muted-video while still seeing the remote video, with the camera
    // toggle available in-call.
    private fun onAnswerWithoutCamera(notificationData: CallNotificationData) {
        answerCall(notificationData, startVideoMuted = true)
    }

    private fun answerCall(notificationData: CallNotificationData, startVideoMuted: Boolean) {
        stopRingtone()
        if (!answerWithoutUnlocking && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Dismiss the keyguard so the call screen is reachable when answering from the
            // lock screen. With AnswerCallOnLockScreen we skip this and let the call open
            // over the lock screen (ElementCallActivity is shown-when-locked).
            // requestDismissKeyguard needs API 26; below that the window flags set in
            // onCreate are what get us past the keyguard.
            getSystemService<KeyguardManager>()?.requestDismissKeyguard(this, null)
        }
        elementCallEntryPoint.startCall(
            CallData(
                sessionId = notificationData.sessionId,
                roomId = notificationData.roomId,
                isAudioCall = notificationData.audioOnly,
                notifyEventId = notificationData.eventId.value,
                startVideoMuted = startVideoMuted,
            )
        )
    }

    private fun onCancel() {
        stopRingtone()
        val activeCall = activeCallManager.activeCall.value ?: return
        appCoroutineScope.launch {
            activeCallManager.hangUpCall(callData = activeCall.callData)
        }
    }

    override fun onDestroy() {
        stopRingtone()
        super.onDestroy()
    }

    private fun startRingtone() {
        if (ringtonePlayer != null) return
        // Respect the ringer: stay quiet on silent/vibrate, like a normal phone call.
        val audioManager = getSystemService<AudioManager>()
        if (audioManager?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
            ?: return
        // The resolved ringtone can be unplayable - e.g. a custom MIUI ringtone backed by
        // another app's private file we are not allowed to read (EACCES). A ringtone that
        // fails to start must not crash the incoming-call screen, so build the player
        // defensively and stay silent on any failure.
        val player = MediaPlayer()
        try {
            player.setDataSource(this, uri)
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            player.isLooping = true
            player.setOnErrorListener { _, _, _ -> true }
            player.prepare()
            player.start()
            ringtonePlayer = player
        } catch (e: Exception) {
            player.release()
            Timber.w(e, "Unable to play incoming call ringtone")
        }
    }

    private fun stopRingtone() {
        ringtonePlayer?.runCatchingExceptions {
            stop()
            release()
        }
        ringtonePlayer = null
    }
}
