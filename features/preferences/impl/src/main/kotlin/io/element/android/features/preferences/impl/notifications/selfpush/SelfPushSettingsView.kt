/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.notifications.selfpush

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.designsystem.components.dialogs.ListDialog
import io.element.android.libraries.designsystem.components.dialogs.ListOption
import io.element.android.libraries.designsystem.components.dialogs.SingleSelectionDialog
import io.element.android.libraries.designsystem.components.list.TextFieldListItem
import io.element.android.libraries.designsystem.components.preferences.PreferenceCategory
import io.element.android.libraries.designsystem.components.preferences.PreferencePage
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextButton
import io.element.android.libraries.designsystem.utils.OnLifecycleEvent
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushProtocol
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.collections.immutable.persistentListOf

@Composable
fun SelfPushSettingsView(
    state: SelfPushSettingsState,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnLifecycleEvent { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> state.eventSink.invoke(SelfPushSettingsEvent.RefreshStatus)
            else -> Unit
        }
    }
    PreferencePage(
        modifier = modifier,
        onBackClick = onBackClick,
        title = stringResource(id = CommonStrings.common_built_in_push),
    ) {
        PreferenceCategory {
            ListItem(
                content = {
                    Text(text = stringResource(id = CommonStrings.screen_notifications_listener_status_title))
                },
                supportingContent = {
                    Text(
                        text = stringResource(
                            id = if (state.listenerConnected) {
                                CommonStrings.screen_notifications_listener_status_connected
                            } else {
                                CommonStrings.screen_notifications_listener_status_connecting
                            }
                        )
                    )
                },
            )
            if (!state.isActive) {
                ListItem(
                    content = {
                        Text(text = stringResource(id = CommonStrings.screen_notifications_builtin_push_enable))
                    },
                    onClick = { state.eventSink(SelfPushSettingsEvent.UseBuiltInPush) },
                )
            }
        }
        PreferenceCategory(title = stringResource(id = CommonStrings.common_advanced_settings)) {
            ListItem(
                content = {
                    Text(text = stringResource(id = CommonStrings.screen_notifications_push_server_title))
                },
                supportingContent = {
                    Text(
                        text = state.server.customServer
                            ?: stringResource(
                                id = CommonStrings.screen_notifications_push_server_default_subtitle,
                                state.server.effectiveServer,
                            )
                    )
                },
                onClick = { state.eventSink(SelfPushSettingsEvent.ShowServerDialog) },
            )
            ListItem(
                content = {
                    Text(text = stringResource(id = CommonStrings.screen_notifications_connection_protocol_title))
                },
                supportingContent = {
                    Text(text = protocolLabel(state.protocol.protocol))
                },
                onClick = { state.eventSink(SelfPushSettingsEvent.ShowProtocolDialog) },
            )
            ListItem(
                content = {
                    Text(text = stringResource(id = CommonStrings.screen_notifications_battery_optimization_title))
                },
                supportingContent = {
                    Text(
                        text = stringResource(
                            id = if (state.batteryOptimizationIgnored) {
                                CommonStrings.screen_notifications_battery_optimization_unrestricted
                            } else {
                                CommonStrings.screen_notifications_battery_optimization_restricted
                            }
                        )
                    )
                },
                onClick = {
                    if (!state.batteryOptimizationIgnored) {
                        state.eventSink(SelfPushSettingsEvent.RequestDisableBatteryOptimization)
                    }
                },
            )
        }
        if (state.server.showDialog) {
            ServerDialog(server = state.server, eventSink = state.eventSink)
        }
        if (state.protocol.showDialog) {
            ProtocolDialog(protocol = state.protocol, eventSink = state.eventSink)
        }
    }
}

@Composable
private fun protocolLabel(protocol: SelfPushProtocol) = stringResource(
    id = when (protocol) {
        SelfPushProtocol.WEBSOCKET -> CommonStrings.screen_notifications_connection_protocol_websocket
        SelfPushProtocol.JSON_STREAM -> CommonStrings.screen_notifications_connection_protocol_json_stream
    }
)

@Composable
private fun ProtocolDialog(
    protocol: SelfPushSettingsState.ProtocolState,
    eventSink: (SelfPushSettingsEvent) -> Unit,
) {
    SingleSelectionDialog(
        title = stringResource(id = CommonStrings.screen_notifications_connection_protocol_title),
        options = persistentListOf(
            ListOption(title = stringResource(id = CommonStrings.screen_notifications_connection_protocol_websocket)),
            ListOption(title = stringResource(id = CommonStrings.screen_notifications_connection_protocol_json_stream)),
        ),
        initialSelection = when (protocol.protocol) {
            SelfPushProtocol.WEBSOCKET -> 0
            SelfPushProtocol.JSON_STREAM -> 1
        },
        onSelectOption = { index ->
            val selected = when (index) {
                0 -> SelfPushProtocol.WEBSOCKET
                else -> SelfPushProtocol.JSON_STREAM
            }
            eventSink(SelfPushSettingsEvent.SetProtocol(selected))
        },
        onDismissRequest = { eventSink(SelfPushSettingsEvent.DismissProtocolDialog) },
    )
}

@Composable
private fun ServerDialog(
    server: SelfPushSettingsState.ServerState,
    eventSink: (SelfPushSettingsEvent) -> Unit,
) {
    var serverUrl by rememberSaveable { mutableStateOf(server.customServer ?: server.effectiveServer) }
    var readToken by rememberSaveable { mutableStateOf(server.readToken.orEmpty()) }
    val trimmedUrl = serverUrl.trim()
    val isValid = isValidSelfPushServerUrl(trimmedUrl)
    ListDialog(
        title = stringResource(id = CommonStrings.screen_notifications_push_server_dialog_title),
        submitText = stringResource(CommonStrings.action_save),
        onSubmit = { eventSink(SelfPushSettingsEvent.SaveServer(trimmedUrl, readToken)) },
        onDismissRequest = { eventSink(SelfPushSettingsEvent.DismissServerDialog) },
        enabled = isValid,
    ) {
        item {
            TextFieldListItem(
                placeholder = DEFAULT_SELF_PUSH_SERVER,
                text = serverUrl,
                onTextChange = { serverUrl = it },
                error = if (serverUrl.isNotEmpty() && !isValid) {
                    stringResource(id = CommonStrings.screen_notifications_push_server_invalid_url)
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
            )
        }
        item {
            TextFieldListItem(
                placeholder = "tk_...",
                text = readToken,
                onTextChange = { readToken = it },
                label = stringResource(id = CommonStrings.screen_notifications_push_server_token_label),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
            )
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = stringResource(id = CommonStrings.screen_notifications_push_server_check_health),
                    onClick = { eventSink(SelfPushSettingsEvent.CheckServerHealth(trimmedUrl, readToken)) },
                    enabled = trimmedUrl.isNotEmpty(),
                )
                // Only surface a result that was produced for the URL and token in the dialog.
                if (server.healthCheckServer == trimmedUrl.trimEnd('/') &&
                    server.healthCheckToken == readToken.trim().takeUnless { it.isEmpty() }
                ) {
                    when (server.healthCheck) {
                        is AsyncData.Loading -> CircularProgressIndicator(
                            modifier = Modifier
                                .progressSemantics()
                                .size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        is AsyncData.Success -> Text(
                            text = stringResource(id = CommonStrings.screen_notifications_push_server_health_ok),
                            style = ElementTheme.typography.fontBodyMdRegular,
                            color = ElementTheme.colors.textSecondary,
                        )
                        is AsyncData.Failure -> Text(
                            text = stringResource(id = CommonStrings.screen_notifications_push_server_health_error),
                            style = ElementTheme.typography.fontBodyMdRegular,
                            color = ElementTheme.colors.textCriticalPrimary,
                        )
                        AsyncData.Uninitialized -> Unit
                    }
                }
            }
        }
        item {
            TextButton(
                text = stringResource(id = CommonStrings.screen_notifications_push_server_reset_to_default),
                onClick = { eventSink(SelfPushSettingsEvent.ResetServer) },
                enabled = server.customServer != null,
            )
        }
        if (server.saveFailed) {
            item {
                Text(
                    text = stringResource(id = CommonStrings.screen_notifications_push_server_save_failed),
                    style = ElementTheme.typography.fontBodyMdRegular,
                    color = ElementTheme.colors.textCriticalPrimary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@PreviewsDayNight
@Composable
internal fun SelfPushSettingsViewPreview(
    @PreviewParameter(SelfPushSettingsStatePreviewParam::class) state: SelfPushSettingsState,
) = ElementPreview {
    SelfPushSettingsView(
        state = state,
        onBackClick = {},
    )
}
