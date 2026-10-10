/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.advanced

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.preferences.impl.R
import io.element.android.libraries.architecture.coverage.ExcludeFromCoverage
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.components.preferences.PreferenceCategory
import io.element.android.libraries.designsystem.components.preferences.PreferencePage
import io.element.android.libraries.designsystem.components.preferences.PreferenceSwitch
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.ListSectionHeader
import io.element.android.libraries.designsystem.theme.components.ListSupportingText
import io.element.android.libraries.designsystem.theme.components.ListSupportingTextDefaults
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.utils.snackbar.LocalSnackbarDispatcher
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarHost
import io.element.android.libraries.designsystem.utils.snackbar.collectSnackbarMessageAsState
import io.element.android.libraries.designsystem.utils.snackbar.rememberSnackbarHostState
import io.element.android.libraries.preferences.api.store.UrlPreviewValue
import io.element.android.libraries.ui.strings.CommonStrings

@Composable
fun AdvancedSettingsView(
    state: AdvancedSettingsState,
    onBackClick: () -> Unit,
    onOpenMxtrSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarDispatcher = LocalSnackbarDispatcher.current
    val snackbarMessage by snackbarDispatcher.collectSnackbarMessageAsState()
    val snackbarHostState = rememberSnackbarHostState(snackbarMessage = snackbarMessage)

    PreferencePage(
        modifier = modifier,
        onBackClick = onBackClick,
        title = stringResource(id = CommonStrings.common_advanced_settings),
        snackbarHost = {
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.navigationBarsPadding()
            )
        }
    ) {
        ListItem(
            content = {
                Text(text = stringResource(id = R.string.screen_advanced_settings_mxtr_title))
            },
            supportingContent = {
                Text(text = stringResource(id = R.string.screen_advanced_settings_mxtr_subtitle))
            },
            leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.LockSolid())),
            onClick = onOpenMxtrSettings,
        )
        // The wifi-only auto-download toggle stays hidden until the matrix media cache
        // story is fully sorted: on app restart Coil's disk cache isn't reliably
        // populated for matrix thumbnails, which made the gate surface the
        // tap-to-download prompt for every image again. The preference plumbing
        // (state, event, store) is in place, so re-enabling is just adding the
        // PreferenceSwitch for state.mediaAutoDownloadOnWifiOnly here.
        PreferenceSwitch(
            title = stringResource(R.string.screen_advanced_settings_hide_redacted_events_toggle_title),
            subtitle = stringResource(R.string.screen_advanced_settings_hide_redacted_events_toggle_subtitle),
            isChecked = state.hideRedactedEvents,
            onCheckedChange = {
                state.eventSink(AdvancedSettingsEvent.SetHideRedactedEvents(it))
            },
        )
        LinkPreviews(state)
    }
}

@Composable
private fun LinkPreviews(
    state: AdvancedSettingsState,
    modifier: Modifier = Modifier,
) {
    PreferenceCategory(
        modifier = modifier,
        title = stringResource(R.string.screen_advanced_settings_show_link_previews_title),
        showTopDivider = true
    ) {
        ListSectionHeader(
            title = stringResource(R.string.screen_advanced_settings_show_link_previews_title),
            hasDivider = false,
            description = {
                ListSupportingText(
                    text = stringResource(R.string.screen_advanced_settings_show_link_previews_subtitle),
                    contentPadding = ListSupportingTextDefaults.Padding.None,
                )
            }
        )
        ListItem(
            content = { Text(text = stringResource(R.string.screen_advanced_settings_show_link_previews_off)) },
            leadingContent = ListItemContent.RadioButton(
                selected = state.urlPreviewValue == UrlPreviewValue.Off,
                compact = true
            ),
            onClick = {
                state.eventSink(AdvancedSettingsEvent.SetUrlPreviewValue(UrlPreviewValue.Off))
            },
        )
        ListItem(
            content = { Text(text = stringResource(R.string.screen_advanced_settings_show_link_previews_unencrypted_only)) },
            leadingContent = ListItemContent.RadioButton(
                selected = state.urlPreviewValue == UrlPreviewValue.UnencryptedOnly,
                compact = true
            ),
            onClick = {
                state.eventSink(AdvancedSettingsEvent.SetUrlPreviewValue(UrlPreviewValue.UnencryptedOnly))
            },
        )
        ListItem(
            content = { Text(text = stringResource(R.string.screen_advanced_settings_show_link_previews_all)) },
            leadingContent = ListItemContent.RadioButton(
                selected = state.urlPreviewValue == UrlPreviewValue.On,
                compact = true
            ),
            onClick = {
                state.eventSink(AdvancedSettingsEvent.SetUrlPreviewValue(UrlPreviewValue.On))
            },
        )
    }
}

@PreviewsDayNight
@Composable
internal fun AdvancedSettingsViewPreview(@PreviewParameter(AdvancedSettingsStatePreviewParam::class) state: AdvancedSettingsState) =
    ElementPreview {
        ContentToPreview(state)
    }

@ExcludeFromCoverage
@Composable
private fun ContentToPreview(state: AdvancedSettingsState) {
    AdvancedSettingsView(
        state = state,
        onBackClick = { },
        onOpenMxtrSettings = {},
    )
}
