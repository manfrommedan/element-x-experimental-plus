/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import im.vector.app.features.analytics.plan.MobileScreen
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.call.api.CallData
import io.element.android.features.call.api.CallSummary
import io.element.android.features.call.api.CallSummaryStore
import io.element.android.features.call.impl.data.WidgetMessage
import io.element.android.features.call.impl.utils.ActiveCallManager
import io.element.android.features.call.impl.utils.CallWidgetProvider
import io.element.android.features.call.impl.utils.WidgetMessageInterceptor
import io.element.android.features.call.impl.utils.WidgetMessageSerializer
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.architecture.runCatchingUpdatingState
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.sync.SyncState
import io.element.android.libraries.matrix.api.timeline.item.event.EventType
import io.element.android.libraries.matrix.api.widget.MatrixWidgetDriver
import io.element.android.libraries.network.useragent.UserAgentProvider
import io.element.android.services.analytics.api.ScreenTracker
import io.element.android.services.appnavstate.api.AppForegroundStateService
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

@AssistedInject
class CallScreenPresenter(
    @Assisted private val callData: CallData,
    @Assisted private val navigator: CallScreenNavigator,
    private val callWidgetProvider: CallWidgetProvider,
    userAgentProvider: UserAgentProvider,
    private val clock: SystemClock,
    private val dispatchers: CoroutineDispatchers,
    private val matrixClientsProvider: MatrixClientProvider,
    private val screenTracker: ScreenTracker,
    private val activeCallManager: ActiveCallManager,
    private val languageTagProvider: LanguageTagProvider,
    private val appForegroundStateService: AppForegroundStateService,
    @AppCoroutineScope
    private val appCoroutineScope: CoroutineScope,
    private val widgetMessageSerializer: WidgetMessageSerializer,
    private val callSummaryStore: CallSummaryStore,
) : Presenter<CallScreenState> {
    @AssistedFactory
    interface Factory {
        fun create(callData: CallData, navigator: CallScreenNavigator): CallScreenPresenter
    }

    private val isAudioOnlyCall = callData.isAudioCall
    private val userAgent = userAgentProvider.provide()

    @Composable
    override fun present(): CallScreenState {
        val coroutineScope = rememberCoroutineScope()
        val urlState = remember { mutableStateOf<AsyncData<String>>(AsyncData.Uninitialized) }
        val callWidgetDriver = remember { mutableStateOf<MatrixWidgetDriver?>(null) }
        val messageInterceptor = remember { mutableStateOf<WidgetMessageInterceptor?>(null) }
        var isWidgetLoaded by rememberSaveable { mutableStateOf(false) }
        var ignoreWebViewError by rememberSaveable { mutableStateOf(false) }
        var webViewError by remember { mutableStateOf<String?>(null) }
        // Set on RemoteJoined widget action; drives the persisted duration at hangup.
        var callConnectedAtMs by rememberSaveable { mutableStateOf<Long?>(null) }
        // m.call.notify event id - captured from the widget send_event response (caller)
        // or threaded through callData.notifyEventId (recipient via incoming notification).
        var notifyEventId by rememberSaveable {
            mutableStateOf(callData.notifyEventId)
        }
        val pendingNotifyRequestIds = remember { mutableSetOf<String>() }
        // Hangup and Close can both fire; this guards against double persistence.
        var summarySaved by rememberSaveable { mutableStateOf(false) }
        val languageTag = languageTagProvider.provideLanguageTag()
        val theme = if (ElementTheme.isLightTheme) "light" else "dark"

        DisposableEffect(Unit) {
            coroutineScope.launch {
                // Sets the call as joined
                activeCallManager.joinedCall(callData)
                fetchRoomCallUrl(
                    callData = callData,
                    urlState = urlState,
                    callWidgetDriver = callWidgetDriver,
                    languageTag = languageTag,
                    theme = theme,
                )
            }
            onDispose {
                appCoroutineScope.launch { activeCallManager.hangUpCall(callData) }
            }
        }
        screenTracker.TrackScreen(screen = MobileScreen.ScreenName.RoomCall)
        HandleMatrixClientSyncState()

        callWidgetDriver.value?.let { driver ->
            LaunchedEffect(Unit) {
                driver.incomingMessages
                    .onEach {
                        // Relay message to the WebView
                        messageInterceptor.value?.sendMessage(it)
                        if (notifyEventId == null && pendingNotifyRequestIds.isNotEmpty()) {
                            extractNotifyEventId(it, pendingNotifyRequestIds)?.let { eventId ->
                                notifyEventId = eventId
                            }
                        }
                    }
                    .launchIn(this)

                driver.run()
            }
        }

        messageInterceptor.value?.let { interceptor ->
            LaunchedEffect(Unit) {
                interceptor.interceptedMessages
                    .onEach {
                        // We are receiving messages from the WebView, consider that the application is loaded
                        ignoreWebViewError = true
                        // Relay message to Widget Driver
                        callWidgetDriver.value?.send(it)

                        val parsedMessage = parseMessage(it)
                        if (parsedMessage?.direction == WidgetMessage.Direction.FromWidget) {
                            when (parsedMessage.action) {
                                WidgetMessage.Action.Close -> {
                                    if (!summarySaved) {
                                        summarySaved = true
                                        saveCallSummary(notifyEventId, callConnectedAtMs)
                                    }
                                    close(callWidgetDriver.value, navigator)
                                }
                                WidgetMessage.Action.ContentLoaded -> {
                                    isWidgetLoaded = true
                                    if (isAudioOnlyCall) {
                                        val widgetId = callWidgetDriver.value?.id
                                        Timber.d("Audio-only call: skipping lobby and sending Join (widgetId=$widgetId)")
                                        widgetId?.let { id -> sendJoinMessage(id, interceptor) }
                                    }
                                }
                                WidgetMessage.Action.SendEvent -> {
                                    val eventType = (parsedMessage.data as? JsonObject)
                                        ?.get("type")
                                        ?.let { v -> (v as? JsonPrimitive)?.contentOrNull }
                                    if (eventType == EventType.RTC_NOTIFICATION) {
                                        Timber.d("Tracking RTC notification send (requestId=${parsedMessage.requestId})")
                                        pendingNotifyRequestIds += parsedMessage.requestId
                                    }
                                }
                                WidgetMessage.Action.RemoteJoined -> {
                                    if (callConnectedAtMs == null) {
                                        val nowMs = android.os.SystemClock.elapsedRealtime()
                                        Timber.d("Call connected: remote participant joined at ${nowMs}ms")
                                        callConnectedAtMs = nowMs
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                    .launchIn(this)
            }

            LaunchedEffect(Unit) {
                // Wait for the call to be joined, if it takes too long, we display an error
                delay(10.seconds)

                if (!isWidgetLoaded) {
                    Timber.w("The call took too long to load. Displaying an error before exiting.")

                    // This will display a simple 'Sorry, an error occurred' dialog and force the user to exit the call
                    webViewError = ""
                }
            }
        }

        fun handleEvent(event: CallScreenEvent) {
            when (event) {
                is CallScreenEvent.Hangup -> {
                    if (!summarySaved) {
                        summarySaved = true
                        saveCallSummary(notifyEventId, callConnectedAtMs)
                    }
                    val widgetId = callWidgetDriver.value?.id
                    val interceptor = messageInterceptor.value
                    if (widgetId != null && interceptor != null && isWidgetLoaded) {
                        // If the call was joined, we need to hang up first. Then the UI will be dismissed automatically.
                        sendHangupMessage(widgetId, interceptor)
                        isWidgetLoaded = false
                        coroutineScope.launch {
                            // Wait for a couple of seconds to receive the hangup message
                            // If we don't get it in time, we close the screen anyway
                            delay(2.seconds)
                            close(callWidgetDriver.value, navigator)
                        }
                    } else {
                        coroutineScope.launch {
                            close(callWidgetDriver.value, navigator)
                        }
                    }
                }
                is CallScreenEvent.SetupMessageChannels -> {
                    messageInterceptor.value = event.widgetMessageInterceptor
                }
                is CallScreenEvent.OnWebViewError -> {
                    if (!ignoreWebViewError) {
                        webViewError = event.description.orEmpty()
                    }
                    // Else ignore the error, give a chance the Element Call to recover by itself.
                }
                is CallScreenEvent.WebViewRenderGone -> {
                    // The WebView render process died: it can't recover or send us a
                    // close message, so close the call screen ourselves instead of
                    // stranding the user on a dead blank page.
                    if (!summarySaved) {
                        summarySaved = true
                        saveCallSummary(notifyEventId, callConnectedAtMs)
                    }
                    coroutineScope.launch { close(callWidgetDriver.value, navigator) }
                }
            }
        }

        return CallScreenState(
            urlState = urlState.value,
            webViewError = webViewError,
            userAgent = userAgent,
            isCallActive = isWidgetLoaded,
            isAudioOnlyCall = isAudioOnlyCall,
            eventSink = ::handleEvent,
        )
    }

    private suspend fun fetchRoomCallUrl(
        callData: CallData,
        urlState: MutableState<AsyncData<String>>,
        callWidgetDriver: MutableState<MatrixWidgetDriver?>,
        languageTag: String?,
        theme: String?,
    ) {
        urlState.runCatchingUpdatingState {
            val result = callWidgetProvider.getWidget(
                sessionId = callData.sessionId,
                roomId = callData.roomId,
                clientId = UUID.randomUUID().toString(),
                isAudioCall = callData.isAudioCall,
                languageTag = languageTag,
                theme = theme,
                startVideoMuted = callData.startVideoMuted,
            ).getOrThrow()
            callWidgetDriver.value = result.driver
            Timber.d("Call widget driver initialized for sessionId: ${callData.sessionId}, roomId: ${callData.roomId}")
            result.url
        }
    }

    @Composable
    private fun HandleMatrixClientSyncState() {
        val coroutineScope = rememberCoroutineScope()
        DisposableEffect(Unit) {
            val client = matrixClientsProvider.getOrNull(callData.sessionId) ?: return@DisposableEffect onDispose {
                Timber.w("No MatrixClient found for sessionId, can't send call notification: ${callData.sessionId}")
            }
            coroutineScope.launch {
                Timber.d("Observing sync state in-call for sessionId: ${callData.sessionId}")
                client.syncService.syncState
                    .collect { state ->
                        if (state != SyncState.Running) {
                            appForegroundStateService.updateIsInCallState(true)
                        }
                    }
            }
            onDispose {
                Timber.d("Stopped observing sync state in-call for sessionId: ${callData.sessionId}")
                // Make sure we mark the call as ended in the app state
                appForegroundStateService.updateIsInCallState(false)
            }
        }
    }

    private fun parseMessage(message: String): WidgetMessage? {
        return widgetMessageSerializer.deserialize(message).getOrNull()
    }

    /** Returns the event_id from a fromWidget send_event response matching a tracked requestId, or null. */
    private fun extractNotifyEventId(rawMessage: String, pending: MutableSet<String>): String? {
        val parsed = runCatchingExceptions { Json.parseToJsonElement(rawMessage).jsonObject }.getOrNull() ?: return null
        val api = parsed["api"]?.jsonPrimitive?.contentOrNull
        val action = parsed["action"]?.jsonPrimitive?.contentOrNull
        val response = parsed["response"] as? JsonObject
        if (api != "fromWidget" || action != "send_event" || response == null) return null
        val requestId = parsed["requestId"]?.jsonPrimitive?.contentOrNull ?: return null
        if (!pending.remove(requestId)) return null
        val eventId = response["event_id"]?.jsonPrimitive?.contentOrNull
        Timber.d("Captured notify event_id=$eventId for requestId=$requestId")
        return eventId
    }

    private fun saveCallSummary(notifyEventId: String?, callConnectedAtMs: Long?) {
        val eventId = notifyEventId ?: run {
            Timber.d("Skipping call summary persistence: no notify event_id captured")
            return
        }
        val summary = if (callConnectedAtMs != null) {
            val durationSeconds = ((android.os.SystemClock.elapsedRealtime() - callConnectedAtMs) / 1_000)
                .coerceAtLeast(1)
            CallSummary.Connected(durationSeconds)
        } else {
            CallSummary.NoAnswer
        }
        Timber.d("Persisting call summary $summary for event $eventId")
        appCoroutineScope.launch(dispatchers.io) {
            runCatchingExceptions { callSummaryStore.save(EventId(eventId), summary) }
                .onFailure { Timber.w(it, "Failed to persist call summary for $eventId") }
        }
    }

    private fun sendHangupMessage(widgetId: String, messageInterceptor: WidgetMessageInterceptor) {
        val message = WidgetMessage(
            direction = WidgetMessage.Direction.ToWidget,
            widgetId = widgetId,
            requestId = "widgetapi-${clock.epochMillis()}",
            action = WidgetMessage.Action.HangUp,
            data = null,
        )
        messageInterceptor.sendMessage(widgetMessageSerializer.serialize(message))
    }

    /**
     * Audio-only path: sends an `io.element.join` widget action so Element
     * Call skips the lobby and the bundled voice layout takes over directly.
     * Video calls go through the regular lobby (mic / camera preview).
     */
    private fun sendJoinMessage(widgetId: String, messageInterceptor: WidgetMessageInterceptor) {
        val message = WidgetMessage(
            direction = WidgetMessage.Direction.ToWidget,
            widgetId = widgetId,
            requestId = "widgetapi-${clock.epochMillis()}",
            action = WidgetMessage.Action.Join,
            data = null,
        )
        messageInterceptor.sendMessage(widgetMessageSerializer.serialize(message))
    }

    private fun CoroutineScope.close(widgetDriver: MatrixWidgetDriver?, navigator: CallScreenNavigator) = launch(dispatchers.io) {
        navigator.close()
        widgetDriver?.close()
    }
}
