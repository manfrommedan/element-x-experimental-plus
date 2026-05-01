/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.utils

import com.google.common.truth.Truth.assertThat
import io.element.android.features.call.impl.utils.DefaultCallWidgetProvider
import io.element.android.features.call.impl.utils.appendUrlParam
import io.element.android.features.call.impl.utils.phoneStyleUrlParams
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.widget.CallWidgetSettingsProvider
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.FakeMatrixClientProvider
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.widget.FakeCallWidgetSettingsProvider
import io.element.android.libraries.matrix.test.widget.FakeMatrixWidgetDriver
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import io.element.android.libraries.preferences.test.InMemoryAppPreferencesStore
import io.element.android.services.appnavstate.api.ActiveRoomsHolder
import io.element.android.services.appnavstate.impl.DefaultActiveRoomsHolder
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultCallWidgetProviderTest {
    @Test
    fun `getWidget - fails if the session does not exist`() = runTest {
        val provider = createProvider(matrixClientProvider = FakeMatrixClientProvider { Result.failure(Exception("Session not found")) })
        assertThat(provider.getWidget(A_SESSION_ID, A_ROOM_ID, false, "clientId", "languageTag", "theme").isFailure).isTrue()
    }

    @Test
    fun `getWidget - fails if the room does not exist`() = runTest {
        val client = FakeMatrixClient().apply {
            givenGetRoomResult(A_ROOM_ID, null)
        }
        val provider = createProvider(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) })
        assertThat(provider.getWidget(A_SESSION_ID, A_ROOM_ID, true, "clientId", "languageTag", "theme").isFailure).isTrue()
    }

    @Test
    fun `getWidget - fails if it can't generate the URL for the widget`() = runTest {
        val room = FakeJoinedRoom(
            generateWidgetWebViewUrlResult = { _, _, _, _ -> Result.failure(Exception("Can't generate URL for widget")) }
        )
        val client = FakeMatrixClient().apply {
            givenGetRoomResult(A_ROOM_ID, room)
        }
        val provider = createProvider(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) })
        assertThat(provider.getWidget(A_SESSION_ID, A_ROOM_ID, false, "clientId", "languageTag", "theme").isFailure).isTrue()
    }

    @Test
    fun `getWidget - fails if it can't get the widget driver`() = runTest {
        val room = FakeJoinedRoom(
            generateWidgetWebViewUrlResult = { _, _, _, _ -> Result.success("url") },
            getWidgetDriverResult = { Result.failure(Exception("Can't get a widget driver")) }
        )
        val client = FakeMatrixClient().apply {
            givenGetRoomResult(A_ROOM_ID, room)
        }
        val provider = createProvider(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) })
        assertThat(provider.getWidget(A_SESSION_ID, A_ROOM_ID, false, "clientId", "languageTag", "theme").isFailure).isTrue()
    }

    @Test
    fun `getWidget - returns a widget driver when all steps are successful`() = runTest {
        val room = FakeJoinedRoom(
            generateWidgetWebViewUrlResult = { _, _, _, _ -> Result.success("url") },
            getWidgetDriverResult = { Result.success(FakeMatrixWidgetDriver()) },
        )
        val client = FakeMatrixClient().apply {
            givenGetRoomResult(A_ROOM_ID, room)
        }
        val provider = createProvider(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) })
        assertThat(provider.getWidget(A_SESSION_ID, A_ROOM_ID, false, "clientId", "languageTag", "theme").getOrNull()).isNotNull()
    }

    @Test
    fun `getWidget - reuses the active room if possible`() = runTest {
        val client = FakeMatrixClient().apply {
            // No room from the client
            givenGetRoomResult(A_ROOM_ID, null)
        }
        val activeRoomsHolder = DefaultActiveRoomsHolder().apply {
            // A current active room with the same room id
            addRoom(
                FakeJoinedRoom(
                    baseRoom = FakeBaseRoom(roomId = A_ROOM_ID),
                    generateWidgetWebViewUrlResult = { _, _, _, _ -> Result.success("url") },
                    getWidgetDriverResult = { Result.success(FakeMatrixWidgetDriver()) },
                )
            )
        }
        val provider = createProvider(
            matrixClientProvider = FakeMatrixClientProvider { Result.success(client) },
            activeRoomsHolder = activeRoomsHolder
        )
        assertThat(provider.getWidget(A_SESSION_ID, A_ROOM_ID, false, "clientId", "languageTag", "theme").isSuccess).isTrue()
    }

    @Test
    fun `getWidget - appends the full audio param set when flag is enabled and call is audio-only`() = runTest {
        val widgetUrl = "https://appassets.androidplatform.net/element-call/index.html#?widgetId=w&parentUrl=p&intent=audio"
        val provider = createProviderWithFixedUrl(widgetUrl, phoneStyleEnabled = true)

        val result = provider.getWidget(A_SESSION_ID, A_ROOM_ID, isAudioCall = true, "clientId", "languageTag", "theme")

        assertThat(result.getOrThrow().url).isEqualTo(
            "$widgetUrl&skipLobby=true&phoneVoiceLayout=true&waitForCallPickup=true&sendNotificationType=ring&autoLeave=true"
        )
    }

    @Test
    fun `getWidget - appends only skipLobby for video calls when flag is enabled`() = runTest {
        val widgetUrl = "https://appassets.androidplatform.net/element-call/index.html#?widgetId=w&parentUrl=p&intent=video"
        val provider = createProviderWithFixedUrl(widgetUrl, phoneStyleEnabled = true)

        val result = provider.getWidget(A_SESSION_ID, A_ROOM_ID, isAudioCall = false, "clientId", "languageTag", "theme")

        assertThat(result.getOrThrow().url).isEqualTo("$widgetUrl&skipLobby=true")
    }

    @Test
    fun `getWidget - leaves URL untouched for audio calls when flag is disabled`() = runTest {
        val widgetUrl = "https://appassets.androidplatform.net/element-call/index.html#?widgetId=w&parentUrl=p&intent=audio"
        val provider = createProviderWithFixedUrl(widgetUrl, phoneStyleEnabled = false)

        val result = provider.getWidget(A_SESSION_ID, A_ROOM_ID, isAudioCall = true, "clientId", "languageTag", "theme")

        assertThat(result.getOrThrow().url).isEqualTo(widgetUrl)
    }

    @Test
    fun `getWidget - leaves URL untouched for video calls when flag is disabled`() = runTest {
        val widgetUrl = "https://appassets.androidplatform.net/element-call/index.html#?widgetId=w&parentUrl=p&intent=video"
        val provider = createProviderWithFixedUrl(widgetUrl, phoneStyleEnabled = false)

        val result = provider.getWidget(A_SESSION_ID, A_ROOM_ID, isAudioCall = false, "clientId", "languageTag", "theme")

        assertThat(result.getOrThrow().url).isEqualTo(widgetUrl)
    }

    @Test
    fun `phoneStyleUrlParams - flag off returns no params`() {
        assertThat(phoneStyleUrlParams(phoneStyleEnabled = false, isAudioCall = true)).isEmpty()
        assertThat(phoneStyleUrlParams(phoneStyleEnabled = false, isAudioCall = false)).isEmpty()
    }

    @Test
    fun `phoneStyleUrlParams - flag on with video returns only skipLobby`() {
        assertThat(phoneStyleUrlParams(phoneStyleEnabled = true, isAudioCall = false))
            .containsExactly("skipLobby=true")
    }

    @Test
    fun `phoneStyleUrlParams - flag on with audio returns the ring-the-room set`() {
        assertThat(phoneStyleUrlParams(phoneStyleEnabled = true, isAudioCall = true)).containsExactly(
            "skipLobby=true",
            "phoneVoiceLayout=true",
            "waitForCallPickup=true",
            "sendNotificationType=ring",
            "autoLeave=true",
        ).inOrder()
    }

    private fun createProviderWithFixedUrl(
        widgetUrl: String,
        phoneStyleEnabled: Boolean,
    ): DefaultCallWidgetProvider {
        return createProvider(
            matrixClientProvider = FakeMatrixClientProvider {
                Result.success(
                    FakeMatrixClient().apply {
                        givenGetRoomResult(
                            A_ROOM_ID,
                            FakeJoinedRoom(
                                generateWidgetWebViewUrlResult = { _, _, _, _ -> Result.success(widgetUrl) },
                                getWidgetDriverResult = { Result.success(FakeMatrixWidgetDriver()) },
                            )
                        )
                    }
                )
            },
            featureFlagService = FakeFeatureFlagService(
                initialState = mapOf(FeatureFlags.PhoneVoiceLayout.key to phoneStyleEnabled),
            ),
        )
    }

    @Test
    fun `appendUrlParam - opens a fragment query when none exists`() {
        val out = appendUrlParam("https://example.com/page", "phoneVoiceLayout=true")
        assertThat(out).isEqualTo("https://example.com/page#?phoneVoiceLayout=true")
    }

    @Test
    fun `appendUrlParam - extends existing fragment query`() {
        val out = appendUrlParam(
            "https://example.com/page#?widgetId=w&intent=audio",
            "phoneVoiceLayout=true",
        )
        assertThat(out).isEqualTo("https://example.com/page#?widgetId=w&intent=audio&phoneVoiceLayout=true")
    }

    @Test
    fun `appendUrlParam - opens fragment query when fragment has no params yet`() {
        val out = appendUrlParam(
            "https://example.com/page#section",
            "phoneVoiceLayout=true",
        )
        assertThat(out).isEqualTo("https://example.com/page#section?phoneVoiceLayout=true")
    }

    @Test
    fun `appendUrlParam - opens fragment query for trailing-hash URLs`() {
        val out = appendUrlParam("https://example.com/page#", "phoneVoiceLayout=true")
        assertThat(out).isEqualTo("https://example.com/page#?phoneVoiceLayout=true")
    }

    @Test
    fun `getWidget - will use a custom base url if it exists`() = runTest {
        val room = FakeJoinedRoom(
            generateWidgetWebViewUrlResult = { _, _, _, _ -> Result.success("url") },
            getWidgetDriverResult = { Result.success(FakeMatrixWidgetDriver()) },
        )
        val client = FakeMatrixClient().apply {
            givenGetRoomResult(A_ROOM_ID, room)
        }
        val preferencesStore = InMemoryAppPreferencesStore().apply {
            setCustomElementCallBaseUrl("https://custom.element.io")
        }
        val settingsProvider = FakeCallWidgetSettingsProvider()
        val provider = createProvider(
            matrixClientProvider = FakeMatrixClientProvider { Result.success(client) },
            callWidgetSettingsProvider = settingsProvider,
            appPreferencesStore = preferencesStore,
        )
        provider.getWidget(A_SESSION_ID, A_ROOM_ID, false, "clientId", "languageTag", "theme")

        assertThat(settingsProvider.providedBaseUrls).containsExactly("https://custom.element.io")
    }

    private fun createProvider(
        matrixClientProvider: MatrixClientProvider = FakeMatrixClientProvider(),
        appPreferencesStore: AppPreferencesStore = InMemoryAppPreferencesStore(),
        callWidgetSettingsProvider: CallWidgetSettingsProvider = FakeCallWidgetSettingsProvider(),
        activeRoomsHolder: ActiveRoomsHolder = DefaultActiveRoomsHolder(),
        featureFlagService: FeatureFlagService = FakeFeatureFlagService(),
    ) = DefaultCallWidgetProvider(
        matrixClientsProvider = matrixClientProvider,
        appPreferencesStore = appPreferencesStore,
        callWidgetSettingsProvider = callWidgetSettingsProvider,
        activeRoomsHolder = activeRoomsHolder,
        featureFlagService = featureFlagService,
    )
}
