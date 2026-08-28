/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import io.element.android.libraries.core.extensions.runCatchingExceptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * This class manages the audio devices for a WebView.
 *
 * It listens for audio device changes and updates the WebView with the available devices.
 * It also handles the selection of the audio device by the user in the WebView and the default audio device based on the device type.
 *
 * See also: [Element Call controls docs.](https://github.com/element-hq/element-call/blob/livekit/docs/controls.md#audio-devices)
 */
class WebViewAudioManager(
    private val webView: WebView,
    private val coroutineScope: CoroutineScope,
    private val onInvalidAudioDeviceAdded: (InvalidAudioDeviceReason) -> Unit,
    /** Audio-only calls prefer the earpiece over the loudspeaker for the initial route. */
    private val isAudioOnlyCall: Boolean = false,
) {
    private val json by lazy {
        Json {
            encodeDefaults = true
            explicitNulls = false
        }
    }

    /**
     * Whether to disable bluetooth audio devices. This must be done on Android versions lower than Android 12,
     * since the WebView approach breaks when using the legacy Bluetooth audio APIs.
     */
    private val disableBluetoothAudioDevices = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    /**
     * This flag indicates whether the WebView audio is enabled or not. By default, it is enabled.
     */
    private val isWebViewAudioEnabled = AtomicBoolean(true)

    /**
     * The list of device types that are considered as communication devices, sorted by likelihood of it being used for communication.
     * Audio-only calls reorder the built-ins to prefer the earpiece over the loudspeaker.
     */
    private val wantedDeviceTypes: List<Int> = if (isAudioOnlyCall) {
        listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
    } else {
        listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        )
    }

    private val audioDeviceComparator = Comparator<AudioDeviceInfo> { a, b ->
        // If the device type is not in the wantedDeviceTypes list, we give it a high index, (i.e. low priority)
        val indexOfA = wantedDeviceTypes.indexOf(a.type).let { if (it == -1) Int.MAX_VALUE else it }
        val indexOfB = wantedDeviceTypes.indexOf(b.type).let { if (it == -1) Int.MAX_VALUE else it }
        indexOfA.compareTo(indexOfB)
    }

    private val audioManager = webView.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * This wake lock is used to turn off the screen when the proximity sensor is triggered during a call,
     * if the selected audio device is the built-in earpiece.
     */
    private val proximitySensorWakeLock by lazy {
        webView.context.getSystemService<PowerManager>()
            ?.takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
            ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "${webView.context.packageName}:ProximitySensorCallWakeLock")
    }

    /**
     * Used to ensure that only one coroutine can access the proximity sensor wake lock at a time, preventing re-acquiring or re-releasing it.
     */
    private val proximitySensorMutex = Mutex()

    /**
     * True when we route through [AudioManager.setCommunicationDevice] rather than the legacy flags.
     *
     * Deliberately keyed to [Build.VERSION_CODES.TIRAMISU] and not to
     * [Build.VERSION_CODES.S], where the API first appeared: the communication-device API only takes
     * effect in [AudioManager.MODE_IN_COMMUNICATION], and [onCallStarted] keeps Android 12 in
     * [AudioManager.MODE_NORMAL] because communication mode breaks device switching there. Selecting
     * a device through an API that silently no-ops was why tapping the earpiece did nothing on
     * Android 12 while the call stayed on the loudspeaker.
     */
    private val usesCommunicationDeviceApi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * This listener tracks the current communication device and updates the WebView when it changes.
     */
    @get:RequiresApi(Build.VERSION_CODES.S)
    private val commsDeviceChangedListener by lazy {
        AudioManager.OnCommunicationDeviceChangedListener { device ->
            // Whoever moved the route, Element Call is told where it actually ended up. Forcing the
            // route back to what Element Call last asked for is how this used to work, and it turned
            // every disagreement into a tug of war with the WebView's own audio stack.
            Timber.d("Audio device changed, type: ${device?.id}")
            reportEffectiveRoute(device?.id?.toString())
        }
    }

    /**
     * Tells Element Call which device the audio is really coming out of.
     *
     * Without this the picker only ever reflects what was asked for, so a selection the platform
     * refused still looked applied: the user tapped the earpiece, the UI moved, and the call stayed
     * on the loudspeaker with nothing to say otherwise.
     */
    private fun reportEffectiveRoute(deviceId: String?) {
        if (deviceId == null) return
        coroutineScope.launch(Dispatchers.Main) {
            Timber.d("Audio: reporting effective route $deviceId to Element Call")
            webView.evaluateJavascript("controls.setAudioDevice(\"$deviceId\");", null)
        }
    }

    /**
     * This callback is used to listen for audio device changes coming from the OS.
     */
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            val validNewDevices = addedDevices.orEmpty().filter { it.type in wantedDeviceTypes && it.isSink }
            if (validNewDevices.isEmpty()) return

            // We need to calculate the available devices ourselves, since calling `listAudioDevices` will return an outdated list
            val audioDevices = (listAudioDevices() + validNewDevices).distinctBy { it.id }.sortedWith(audioDeviceComparator)
            setAvailableAudioDevices(audioDevices.map(SerializableAudioDevice::fromAudioDeviceInfo))
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            // Update the available devices
            // Element Call will then decide to switch devices if needed
            setAvailableAudioDevices()
        }
    }

    /**
     * Previously selected device, used to restore the selection when the selected device is removed.
     */
    private var previousSelectedDevice: AudioDeviceInfo? = null

    /** True once we pinned the route ourselves, so retries don't override Element Call's pick. */
    private var initialRouteSelected = false

    private var hasRegisteredCallbacks = false

    /** Held for the duration of the call so other apps don't yank our stream. Only set on API 26+. */
    private var audioFocusRequest: AudioFocusRequest? = null

    /** We never pause on focus loss, but the pre-26 API needs a listener to hand back later. */
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { /* no-op, we don't pause */ }

    /**
     * Marks if the WebView audio is in call mode or not.
     */
    val isInCallMode = AtomicBoolean(false)

    init {
        Timber.d("Audio: WebViewAudioManager init - isAudioOnlyCall=$isAudioOnlyCall, BT-disabled=$disableBluetoothAudioDevices")
        // Apparently, registering the javascript interface takes a while, so registering and immediately using it doesn't work
        // We register it ahead of time to avoid this issue
        registerWebViewDeviceSelectedCallback()
    }

    /**
     * Call this method when the call starts to enable in-call audio mode.
     *
     * It'll set the audio mode to [AudioManager.MODE_IN_COMMUNICATION] if possible, register the audio device callback and set the available audio devices.
     */
    fun onCallStarted() {
        if (!isInCallMode.compareAndSet(false, true)) {
            Timber.w("Audio: tried to enable webview in-call audio mode while already in it")
            return
        }

        Timber.d("Audio: enabling webview in-call audio mode")

        // MODE_IN_COMMUNICATION lets volume keys control the call volume; pre-Tiramisu
        // breaks audio-device switching, so older releases fall back to MODE_NORMAL.
        val targetMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AudioManager.MODE_IN_COMMUNICATION
        } else {
            AudioManager.MODE_NORMAL
        }
        Timber.d("Audio: setting mode to $targetMode (SDK_INT = ${Build.VERSION.SDK_INT})")
        audioManager.mode = targetMode

        claimVoipAudioFocus()
        ensureCallVolumeIsAudible()
        selectInitialCallRoute()
        setWebViewAndroidNativeBridge()
    }

    /** AUDIOFOCUS_GAIN_TRANSIENT so background music auto-resumes after hangup. */
    private fun claimVoipAudioFocus() {
        runCatchingExceptions {
            // AudioFocusRequest is API 26; below that only the stream-based call exists, and
            // touching the class at all would throw NoClassDefFoundError, which is an Error and
            // would sail straight past runCatchingExceptions.
            val focusResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
                audioManager.requestAudioFocus(request).also { audioFocusRequest = request }
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                )
            }
            Timber.d("Audio: VoIP focus request returned $focusResult")
        }.onFailure { Timber.w(it, "Failed to claim VoIP audio focus") }
    }

    /** Bumps a quietly-set call stream up to AUDIBLE_CALL_VOLUME_TARGET_RATIO of max. */
    private fun ensureCallVolumeIsAudible() {
        runCatchingExceptions {
            val stream = AudioManager.STREAM_VOICE_CALL
            val maxVolume = audioManager.getStreamMaxVolume(stream)
            val currentVolume = audioManager.getStreamVolume(stream)
            val floor = (maxVolume * QUIET_CALL_VOLUME_FLOOR_RATIO).toInt().coerceAtLeast(1)
            val target = (maxVolume * AUDIBLE_CALL_VOLUME_TARGET_RATIO).toInt().coerceAtMost(maxVolume)
            if (currentVolume < floor) {
                Timber.d("Audio: call stream at $currentVolume / $maxVolume - bumping to $target")
                audioManager.setStreamVolume(stream, target, 0)
            } else {
                Timber.d("Audio: call stream at $currentVolume / $maxVolume - leaving alone")
            }
        }.onFailure { Timber.w(it, "Failed to adjust call stream volume") }
    }

    /**
     * Picks the call route as soon as the call starts, before anything has played.
     *
     * [AudioManager.setCommunicationDevice] applies asynchronously, so if we wait until the
     * ringback or the first media is about to play, the first moments go through whatever
     * route the system had before, often the loudspeaker. Pinning the route right after the
     * audio mode and focus are set gives the change time to land. Element Call can still
     * override it later in the device picker, and a user pick in the WebView always wins.
     */
    private fun selectInitialCallRoute() {
        if (initialRouteSelected || previousSelectedDevice != null) return
        // Bluetooth is disabled below Android 12, so it cannot be the default there even
        // though it sorts ahead of the earpiece.
        val device = listAudioDevices()
            .firstOrNull { !disableBluetoothAudioDevices || it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ?: return
        Timber.d("Audio: selecting initial call route: ${deviceName(device.type, device.productName.toString())}")
        audioManager.selectAudioDevice(device)
        initialRouteSelected = true
    }

    private fun abandonVoipAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { request ->
                runCatchingExceptions { audioManager.abandonAudioFocusRequest(request) }
                    .onFailure { Timber.w(it, "Failed to abandon VoIP audio focus") }
                audioFocusRequest = null
            }
        } else {
            runCatchingExceptions {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusChangeListener)
            }.onFailure { Timber.w(it, "Failed to abandon VoIP audio focus") }
        }
    }

    /**
     * Call this method when the call stops to disable in-call audio mode.
     *
     * It's the counterpart of [onCallStarted], and should be called as a pair with it once the call has ended.
     */
    fun onCallStopped() {
        if (!isInCallMode.compareAndSet(true, false)) {
            Timber.w("Audio: tried to disable webview in-call audio mode while already disabled")
            return
        }

        // Since this should run when the call is no longer running, it should be OK to not use the mutex here
        if (proximitySensorWakeLock?.isHeld == true) {
            proximitySensorWakeLock?.release()
        }

        // Stop the ringback and release the route before anything else: a call that ended
        // before playback started never registered the callbacks below, and the early return
        // there used to leave the tone ringing and the earpiece pinned for good.
        setRingbackPlaying(false)
        // A fresh call in this WebView gets to pick its initial route again.
        initialRouteSelected = false

        abandonVoipAudioFocus()

        audioManager.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }

        if (!hasRegisteredCallbacks) {
            Timber.w("Audio: tried to disable webview in-call audio mode without registering callbacks")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.removeOnCommunicationDeviceChangedListener(commsDeviceChangedListener)
        }

        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
    }

    /**
     * Plays the ringback while a call waits to be answered.
     *
     * The dialler used to play it from the WebView through Web Audio, which Android treats as media
     * and sends to the loudspeaker until the call's own audio pulls the route to the earpiece. A
     * tone on the voice call stream follows the call route from the first pulse.
     */
    private var ringbackTone: ToneGenerator? = null

    private fun setRingbackPlaying(playing: Boolean) {
        if (playing) {
            if (ringbackTone != null) return
            // Backstop for selectInitialCallRoute: if no devices were listed when the call
            // started (transient during boot), retry now rather than let the tone follow
            // whatever route the system happened to have.
            selectInitialCallRoute()
            ringbackTone = runCatchingExceptions {
                ToneGenerator(AudioManager.STREAM_VOICE_CALL, RINGBACK_VOLUME).apply {
                    startTone(ToneGenerator.TONE_SUP_RINGTONE)
                }
            }.onFailure { Timber.w(it, "Audio: could not start the ringback tone") }.getOrNull()
        } else {
            ringbackTone?.runCatchingExceptions {
                stopTone()
                release()
            }
            ringbackTone = null
        }
    }

    /**
     * Registers the WebView audio device selected callback.
     *
     * This should be called when the WebView is created to ensure that the callback is set before any audio device selection is made.
     */
    private fun registerWebViewDeviceSelectedCallback() {
        val webViewAudioDeviceSelectedCallback = AndroidWebViewAudioBridge(
            onAudioDeviceSelected = { selectedDeviceId ->
                previousSelectedDevice = listAudioDevices().find { it.id.toString() == selectedDeviceId }
                audioManager.selectAudioDevice(selectedDeviceId)
            },
            onRingingChanged = { ringing ->
                coroutineScope.launch(Dispatchers.Main) { setRingbackPlaying(ringing) }
            },
            onAudioPlaybackStarted = {
                coroutineScope.launch(Dispatchers.Main) {
                    // Even with the callback, it seems like starting the audio takes a bit on the webview side,
                    // so we add an extra delay here to make sure it's ready
                    delay(2.seconds)

                    // Calling this ahead of time makes the default audio device to not use the right audio stream
                    setAvailableAudioDevices()

                    // Registering the audio devices changed callback will also set the default audio device
                    audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        audioManager.addOnCommunicationDeviceChangedListener(Executors.newSingleThreadExecutor(), commsDeviceChangedListener)
                    }

                    hasRegisteredCallbacks = true
                }
            },
        )
        Timber.d("Setting androidNativeBridge javascript interface in webview")
        webView.addJavascriptInterface(webViewAudioDeviceSelectedCallback, "androidNativeBridge")
    }

    /**
     * Assigns the callback in the WebView to be called when the user selects an audio device.
     *
     * It should be called with some delay after [registerWebViewDeviceSelectedCallback].
     */
    private fun setWebViewAndroidNativeBridge() {
        Timber.d("Adding callback in controls.onAudioPlaybackStarted")
        webView.evaluateJavascript("controls.onAudioPlaybackStarted = () => { androidNativeBridge.onTrackReady(); };", null)
        Timber.d("Adding callback in controls.onAudioDeviceSelect")
        webView.evaluateJavascript("controls.onAudioDeviceSelect = (id) => { androidNativeBridge.setAudioDevice(id); };", null)
        // Only the dialler hands its ringback over. Without the flag Element Call keeps playing its
        // own, exactly as it does everywhere else.
        if (isAudioOnlyCall) {
            Timber.d("Adding callback in controls.onRingingChanged")
            webView.evaluateJavascript(
                "controls.onRingingChanged = (ringing) => { androidNativeBridge.setRinging(ringing); };",
                null,
            )
        }
    }

    /**
     * Returns the list of available audio devices, sorted by likelihood of it being used for communication.
     *
     * On Android 11 ([Build.VERSION_CODES.R]) and lower, it returns the list of output devices as a fallback.
     */
    private fun listAudioDevices(): List<AudioDeviceInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
        } else {
            val rawAudioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            rawAudioDevices.filter { it.type in wantedDeviceTypes && it.isSink }
        }.sortedWith(audioDeviceComparator)
    }

    /**
     * Sets the available audio devices in the WebView.
     *
     * @param devices The list of audio devices to set. If not provided, it will use the current list of audio devices.
     */
    private fun setAvailableAudioDevices(
        devices: List<SerializableAudioDevice> = listAudioDevices().map(SerializableAudioDevice::fromAudioDeviceInfo),
    ) {
        Timber.d("Updating available audio devices")
        val deviceList = json.encodeToString(devices)
        webView.evaluateJavascript("controls.setAvailableAudioDevices($deviceList);", {
            Timber.d("Audio: setAvailableAudioDevices result: $it")
        })
    }

    /**
     * Selects the audio device on the OS based on the provided device id.
     *
     * It will select the device only if it is available in the list of audio devices.
     *
     * @param device The id of the audio device to select.
     */
    private fun AudioManager.selectAudioDevice(device: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val audioDevice = availableCommunicationDevices.find { it.id.toString() == device }
            selectAudioDevice(audioDevice)
        } else {
            val rawAudioDevices = getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val audioDevice = rawAudioDevices.find { it.id.toString() == device }
            selectAudioDevice(audioDevice)
        }
    }

    /**
     * Selects the audio device on the OS based on the provided device info.
     *
     * @param device The info of the audio device to select, or none to clear the selected device.
     */
    private fun AudioManager.selectAudioDevice(device: AudioDeviceInfo?) {
        if (usesCommunicationDeviceApi) {
            if (device != null) {
                val applied = runCatchingExceptions {
                    Timber.d("Setting communication device: ${device.id} - ${deviceName(device.type, device.productName.toString())}")
                    setCommunicationDevice(device)
                }.onFailure {
                    Timber.e(it, "Could not set communication device.")
                }.getOrDefault(false)
                if (!applied) {
                    // Some vendors reject setCommunicationDevice while their own stack is mid-handover.
                    // Falling back keeps the route honest instead of leaving the user on the loudspeaker.
                    Timber.w("Audio: setCommunicationDevice refused ${device.id}, falling back to the legacy route")
                    selectAudioDeviceLegacy(device)
                    // The refusal means no listener callback is coming, so report what we settled on.
                    reportEffectiveRoute(communicationDevice?.id?.toString() ?: device.id.toString())
                }
            } else {
                runCatchingExceptions {
                    clearCommunicationDevice()
                }.onFailure {
                    Timber.e(it, "Could not clear communication device.")
                }
            }
        } else {
            selectAudioDeviceLegacy(device)
            // No communication device is ever set on this path, so the change listener stays silent
            // and this is the only chance to tell Element Call where the audio actually went.
            reportEffectiveRoute(device?.id?.toString())
        }

        updateProximityForDevice(device)
    }

    /**
     * Routes audio the pre-[Build.VERSION_CODES.S] way, by toggling the speakerphone and SCO flags.
     *
     * This is also the fallback whenever [AudioManager.setCommunicationDevice] refuses a device, and
     * the only path we use while the audio mode is [AudioManager.MODE_NORMAL], because the
     * communication-device API is a no-op outside of communication mode.
     */
    @Suppress("DEPRECATION")
    private fun AudioManager.selectAudioDeviceLegacy(device: AudioDeviceInfo?) {
        if (device != null) {
            if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && disableBluetoothAudioDevices) {
                Timber.w("Bluetooth audio devices are disabled on this Android version")
                setAudioEnabled(false)
                onInvalidAudioDeviceAdded(InvalidAudioDeviceReason.BT_AUDIO_DEVICE_DISABLED)
                return
            }
            setAudioEnabled(true)
            isSpeakerphoneOn = device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            isBluetoothScoOn = device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        } else {
            isSpeakerphoneOn = false
            isBluetoothScoOn = false
        }
    }

    /** Holds the proximity wake lock only on the earpiece route. */
    private fun updateProximityForDevice(device: AudioDeviceInfo?) {
        coroutineScope.launch {
            proximitySensorMutex.withLock {
                @Suppress("WakeLock", "WakeLockTimeout")
                if (device?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                    if (proximitySensorWakeLock?.isHeld == false) {
                        Timber.d("Audio: acquiring proximity wake lock for earpiece route")
                        proximitySensorWakeLock?.acquire()
                    }
                } else if (proximitySensorWakeLock?.isHeld == true) {
                    Timber.d("Audio: releasing proximity wake lock - route is ${device?.type}")
                    proximitySensorWakeLock?.release()
                }
            }
        }
    }

    /**
     * Sets whether the audio is enabled for Element Call in the WebView.
     * It will only perform the change if the audio state has changed.
     */
    private fun setAudioEnabled(enabled: Boolean) {
        coroutineScope.launch(Dispatchers.Main) {
            Timber.d("Setting audio enabled in Element Call: $enabled")
            if (isWebViewAudioEnabled.getAndSet(enabled) != enabled) {
                webView.evaluateJavascript("controls.setAudioEnabled($enabled);", null)
            }
        }
    }

    private companion object {
        const val QUIET_CALL_VOLUME_FLOOR_RATIO = 0.5f
        const val AUDIBLE_CALL_VOLUME_TARGET_RATIO = 0.8f
    }
}

/**
 * This class is used to handle the audio device selection in the WebView.
 * It listens for the audio device selection event and calls the callback with the selected device ID.
 */
private class AndroidWebViewAudioBridge(
    private val onAudioDeviceSelected: (String) -> Unit,
    private val onRingingChanged: (Boolean) -> Unit,
    private val onAudioPlaybackStarted: () -> Unit,
) {
    @JavascriptInterface
    fun setRinging(ringing: Boolean) {
        Timber.d("Ringing changed in webview: $ringing")
        onRingingChanged(ringing)
    }

    @JavascriptInterface
    fun setAudioDevice(id: String) {
        Timber.d("Audio device selected in webview, id: $id")
        onAudioDeviceSelected(id)
    }

    @JavascriptInterface
    fun onTrackReady() {
        // This method can be used to notify the WebView that the audio track is ready
        // It can be used to start playing audio or to update the UI
        Timber.d("Audio track is ready")

        onAudioPlaybackStarted()
    }
}

private fun deviceName(type: Int, name: String): String {
    // TODO maybe translate these?
    val typePart = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Built-in earpiece"
        else -> "Unknown"
    }
    return if (isBuiltIn(type)) {
        typePart
    } else {
        "$typePart - $name"
    }
}

private fun isBuiltIn(type: Int): Boolean = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
    AudioDeviceInfo.TYPE_BUILTIN_MIC,
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> true
    else -> false
}

enum class InvalidAudioDeviceReason {
    BT_AUDIO_DEVICE_DISABLED,
}

/**
 * This class is used to serialize the audio device information to JSON.
 */
@Suppress("unused")
@Serializable
internal data class SerializableAudioDevice(
    val id: String,
    val name: String,
    @Transient val type: Int = 0,
    // These have to be part of the constructor for the JSON serializer to pick them up
    val isEarpiece: Boolean = type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
    val isSpeaker: Boolean = type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    val isExternalHeadset: Boolean = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
) {
    companion object {
        fun fromAudioDeviceInfo(audioDeviceInfo: AudioDeviceInfo): SerializableAudioDevice {
            return SerializableAudioDevice(
                id = audioDeviceInfo.id.toString(),
                name = deviceName(type = audioDeviceInfo.type, name = audioDeviceInfo.productName.toString()),
                type = audioDeviceInfo.type,
            )
        }
    }
}

/** Loud enough to hear against the earpiece, quiet enough not to startle. */
private const val RINGBACK_VOLUME = 80
