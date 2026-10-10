/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.advanced

import io.element.android.libraries.preferences.api.store.UrlPreviewValue

sealed interface AdvancedSettingsEvent {
    data class SetUrlPreviewValue(val value: UrlPreviewValue) : AdvancedSettingsEvent
    data class SetHideRedactedEvents(val value: Boolean) : AdvancedSettingsEvent
    data class SetMediaAutoDownloadOnWifiOnly(val value: Boolean) : AdvancedSettingsEvent
}
