/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.notifications.selfpush

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushProtocol

open class SelfPushSettingsStatePreviewParam : PreviewParameterProvider<SelfPushSettingsState> {
    override val values: Sequence<SelfPushSettingsState>
        get() = sequenceOf(
            aSelfPushSettingsState(),
            aSelfPushSettingsState(
                isActive = true,
                listenerStatus = SelfPushSettingsState.ListenerStatus.CONNECTED,
            ),
            aSelfPushSettingsState(
                isActive = true,
                listenerStatus = SelfPushSettingsState.ListenerStatus.RECONNECTING,
            ),
            aSelfPushSettingsState(
                isActive = true,
                listenerStatus = SelfPushSettingsState.ListenerStatus.REFUSED,
            ),
            aSelfPushSettingsState(
                isActive = true,
                listenerStatus = SelfPushSettingsState.ListenerStatus.CONNECTED,
                server = aServerState(showDialog = true),
            ),
            aSelfPushSettingsState(
                isActive = true,
                listenerStatus = SelfPushSettingsState.ListenerStatus.CONNECTED,
                server = aServerState(
                    customServer = "https://push.example.com",
                    showDialog = true,
                    saveFailed = true,
                ),
            ),
            aSelfPushSettingsState(
                isActive = true,
                protocol = aProtocolState(showDialog = true),
            ),
            aSelfPushSettingsState(
                isActive = true,
                batteryOptimizationIgnored = true,
            ),
        )
}

fun aSelfPushSettingsState(
    isActive: Boolean = false,
    server: SelfPushSettingsState.ServerState = aServerState(),
    protocol: SelfPushSettingsState.ProtocolState = aProtocolState(),
    listenerStatus: SelfPushSettingsState.ListenerStatus = SelfPushSettingsState.ListenerStatus.INACTIVE,
    batteryOptimizationIgnored: Boolean = false,
    eventSink: (SelfPushSettingsEvent) -> Unit = {},
) = SelfPushSettingsState(
    isActive = isActive,
    server = server,
    protocol = protocol,
    listenerStatus = listenerStatus,
    batteryOptimizationIgnored = batteryOptimizationIgnored,
    eventSink = eventSink,
)

fun aServerState(
    customServer: String? = null,
    effectiveServer: String = customServer ?: "https://ntfy.sh",
    showDialog: Boolean = false,
    healthCheck: AsyncData<Unit> = AsyncData.Uninitialized,
    healthCheckServer: String? = null,
    healthCheckToken: String? = null,
    saveFailed: Boolean = false,
    readToken: String? = null,
) = SelfPushSettingsState.ServerState(
    customServer = customServer,
    effectiveServer = effectiveServer,
    showDialog = showDialog,
    healthCheck = healthCheck,
    healthCheckServer = healthCheckServer,
    healthCheckToken = healthCheckToken,
    saveFailed = saveFailed,
    readToken = readToken,
)

fun aProtocolState(
    protocol: SelfPushProtocol = SelfPushProtocol.WEBSOCKET,
    showDialog: Boolean = false,
) = SelfPushSettingsState.ProtocolState(
    protocol = protocol,
    showDialog = showDialog,
)
