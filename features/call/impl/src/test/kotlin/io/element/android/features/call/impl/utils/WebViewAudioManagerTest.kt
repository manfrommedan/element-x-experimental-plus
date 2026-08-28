/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package io.element.android.features.call.impl.utils

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.webkit.WebView
import com.google.common.truth.Truth.assertThat
import io.element.android.tests.testutils.plantTestTimber
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class WebViewAudioManagerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        plantTestTimber()
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `first ring pins the preferred device for this call type`() = runTest {
        useDevices(earpiece(), loudspeaker())
        val manager = newManager(this, isAudioOnlyCall = true)
        manager.onCallStarted()

        manager.simulateRinging(true)
        mainDispatcher.scheduler.advanceUntilIdle()

        assertThat(audioManager.communicationDevice?.type).isEqualTo(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
    }

    @Test
    fun `ring pin never overrides a device picked from the webview`() = runTest {
        useDevices(earpiece(), loudspeaker())
        val manager = newManager(this, isAudioOnlyCall = true)
        manager.onCallStarted()

        manager.simulateWebViewDevicePick(loudspeaker().id)
        manager.simulateRinging(true)
        mainDispatcher.scheduler.advanceUntilIdle()

        assertThat(audioManager.communicationDevice?.type).isEqualTo(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    }

    @Test
    fun `playback start releases our pin when nothing else took over`() = runTest {
        useDevices(earpiece(), loudspeaker())
        val manager = newManager(this, isAudioOnlyCall = true)
        manager.onCallStarted()

        manager.simulateRinging(true)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertThat(audioManager.communicationDevice?.type).isEqualTo(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)

        manager.simulatePlaybackStarted()
        mainDispatcher.scheduler.advanceUntilIdle()

        assertThat(audioManager.communicationDevice).isNull()
    }

    @Test
    fun `playback start keeps element call's own pick instead of releasing it`() = runTest {
        useDevices(earpiece(), loudspeaker())
        val manager = newManager(this, isAudioOnlyCall = true)
        manager.onCallStarted()

        manager.simulateRinging(true)
        manager.simulateWebViewDevicePick(loudspeaker().id)
        manager.simulatePlaybackStarted()
        mainDispatcher.scheduler.advanceUntilIdle()

        assertThat(audioManager.communicationDevice?.type).isEqualTo(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    }

    @Test
    fun `call stop clears the ring pin even when nothing ever played`() = runTest {
        useDevices(earpiece(), loudspeaker())
        val manager = newManager(this, isAudioOnlyCall = true)
        manager.onCallStarted()

        manager.simulateRinging(true)
        mainDispatcher.scheduler.advanceUntilIdle()
        manager.onCallStopped()

        assertThat(audioManager.communicationDevice).isNull()
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_NORMAL)
    }

    private fun newManager(scope: CoroutineScope, isAudioOnlyCall: Boolean): WebViewAudioManager {
        return WebViewAudioManager(
            webView = WebView(context),
            coroutineScope = scope,
            onInvalidAudioDeviceAdded = {},
            isAudioOnlyCall = isAudioOnlyCall,
        )
    }

    private fun useDevices(vararg devices: AudioDeviceInfo) {
        shadowOf(audioManager).setAvailableCommunicationDevices(devices.toList())
    }

    private fun earpiece(): AudioDeviceInfo = aDeviceInfo(id = 1, type = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, name = "earpiece")

    private fun loudspeaker(): AudioDeviceInfo = aDeviceInfo(id = 2, type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, name = "speaker")

    private fun aDeviceInfo(id: Int, type: Int, name: String): AudioDeviceInfo = mockk {
        every { this@mockk.id } returns id
        every { this@mockk.type } returns type
        every { productName } returns name
        every { isSink } returns true
    }

    private fun WebViewAudioManager.simulateRinging(ringing: Boolean) = invokeBridge("setRinging", Boolean::class.javaPrimitiveType, ringing)

    private fun WebViewAudioManager.simulatePlaybackStarted() = invokeBridge("onTrackReady")

    private fun WebViewAudioManager.simulateWebViewDevicePick(deviceId: Int) = invokeBridge("setAudioDevice", String::class.java, deviceId.toString())

    private fun WebViewAudioManager.invokeBridge(method: String, paramType: Class<*>? = null, arg: Any? = null) {
        val field = WebViewAudioManager::class.java.getDeclaredField("webView").apply { isAccessible = true }
        val webView = field.get(this) as WebView
        val bridge = shadowOf(webView).getJavascriptInterface("androidNativeBridge")
        val javaMethod = if (paramType == null) {
            bridge.javaClass.getMethod(method)
        } else {
            bridge.javaClass.getMethod(method, paramType)
        }
        if (arg == null) javaMethod.invoke(bridge) else javaMethod.invoke(bridge, arg)
    }
}
