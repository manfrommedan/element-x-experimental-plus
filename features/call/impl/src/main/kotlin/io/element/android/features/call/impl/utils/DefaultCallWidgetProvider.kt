/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.utils

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.widget.CallWidgetSettingsProvider
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import io.element.android.services.appnavstate.api.ActiveRoomsHolder
import kotlinx.coroutines.flow.firstOrNull
import timber.log.Timber

private const val EMBEDDED_CALL_WIDGET_BASE_URL = "https://appassets.androidplatform.net/element-call/index.html"

/**
 * URL fragment params layered onto the widget URL under the phone-style Labs flag.
 * The audio block mirrors the START_CALL_DM_VOICE intent preset for groups.
 */
internal fun phoneStyleUrlParams(phoneStyleEnabled: Boolean, isAudioCall: Boolean): List<String> = buildList {
    if (!phoneStyleEnabled) return@buildList
    add("skipLobby=true")
    if (isAudioCall) {
        add("phoneVoiceLayout=true")
        add("waitForCallPickup=true")
        add("sendNotificationType=ring")
        add("autoLeave=true")
    }
}

/** Appends `key=value` to an Element Call widget URL fragment (after `#?`). */
internal fun appendUrlParam(url: String, param: String): String {
    val hashIndex = url.indexOf('#')
    return when {
        // No fragment yet, open one with the param as the fragment query.
        hashIndex == -1 -> "$url#?$param"
        // Trailing "#" with empty fragment, start the fragment query.
        hashIndex == url.lastIndex -> "$url?$param"
        // Fragment already carries its own query (the common case for widget
        // URLs that look like .../index.html#?widgetId=...&...): append.
        url.substring(hashIndex + 1).contains('?') -> "$url&$param"
        // Fragment exists but holds no params yet, open a fragment query.
        else -> "$url?$param"
    }
}

@ContributesBinding(AppScope::class)
class DefaultCallWidgetProvider(
    private val matrixClientsProvider: MatrixClientProvider,
    private val appPreferencesStore: AppPreferencesStore,
    private val callWidgetSettingsProvider: CallWidgetSettingsProvider,
    private val activeRoomsHolder: ActiveRoomsHolder,
    private val featureFlagService: FeatureFlagService,
) : CallWidgetProvider {
    override suspend fun getWidget(
        sessionId: SessionId,
        roomId: RoomId,
        isAudioCall: Boolean,
        clientId: String,
        languageTag: String?,
        theme: String?,
        startVideoMuted: Boolean,
    ): Result<CallWidgetProvider.GetWidgetResult> = runCatchingExceptions {
        val matrixClient = matrixClientsProvider.getOrRestore(sessionId).getOrThrow()
        val room = activeRoomsHolder.getActiveRoomMatching(sessionId, roomId)
            ?: matrixClient.getJoinedRoom(roomId)
            ?: error("Room not found")

        val customBaseUrl = appPreferencesStore.getCustomElementCallBaseUrlFlow().firstOrNull()
        val baseUrl = customBaseUrl ?: EMBEDDED_CALL_WIDGET_BASE_URL

        val roomInfo = room.info()
        val isEncrypted = roomInfo.isEncrypted ?: room.getUpdatedIsEncrypted().getOrThrow()
        val widgetSettings = callWidgetSettingsProvider.provide(
            baseUrl = baseUrl,
            encrypted = isEncrypted,
            direct = room.isDm(),
            isAudioCall = isAudioCall,
            hasActiveCall = roomInfo.hasRoomCall,
        )
        val callUrl = room.generateWidgetWebViewUrl(
            widgetSettings = widgetSettings,
            clientId = clientId,
            languageTag = languageTag,
            theme = theme,
        ).getOrThrow()

        val phoneStyleEnabled = featureFlagService.isFeatureEnabled(FeatureFlags.PhoneVoiceLayout)
        val urlParams = buildList {
            addAll(phoneStyleUrlParams(phoneStyleEnabled, isAudioCall))
            if (startVideoMuted && !isAudioCall) {
                // Answer a video call with the camera initially off (Telegram-style):
                // the audio intent starts the local camera muted while keeping the
                // normal layout, so the remote video is still shown and the user can
                // turn their camera on. Independent of the phone-voice layout flag.
                add("callIntent=audio")
                if (!contains("skipLobby=true")) add("skipLobby=true")
            }
        }
        val finalUrl = urlParams.fold(callUrl, ::appendUrlParam)
        Timber.tag("Call").d(
            "Widget URL built; isAudioCall=$isAudioCall, startVideoMuted=$startVideoMuted, phoneStyleEnabled=$phoneStyleEnabled"
        )

        val driver = room.getWidgetDriver(widgetSettings).getOrThrow()

        CallWidgetProvider.GetWidgetResult(
            driver = driver,
            url = finalUrl,
        )
    }
}
