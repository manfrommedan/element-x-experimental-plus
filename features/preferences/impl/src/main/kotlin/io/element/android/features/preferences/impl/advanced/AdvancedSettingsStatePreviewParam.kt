/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.advanced

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.libraries.preferences.api.store.UrlPreviewValue

open class AdvancedSettingsStatePreviewParam : PreviewParameterProvider<AdvancedSettingsState> {
    override val values: Sequence<AdvancedSettingsState>
        get() = sequenceOf(
            aAdvancedSettingsState(),
            aAdvancedSettingsState(urlPreviewValue = UrlPreviewValue.Off),
            aAdvancedSettingsState(urlPreviewValue = UrlPreviewValue.On),
            aAdvancedSettingsState(hideRedactedEvents = true),
        )
}

fun aAdvancedSettingsState(
    urlPreviewValue: UrlPreviewValue = UrlPreviewValue.DEFAULT,
    hideRedactedEvents: Boolean = false,
    mediaAutoDownloadOnWifiOnly: Boolean = false,
    eventSink: (AdvancedSettingsEvent) -> Unit = {},
) = AdvancedSettingsState(
    urlPreviewValue = urlPreviewValue,
    hideRedactedEvents = hideRedactedEvents,
    mediaAutoDownloadOnWifiOnly = mediaAutoDownloadOnWifiOnly,
    eventSink = eventSink
)
