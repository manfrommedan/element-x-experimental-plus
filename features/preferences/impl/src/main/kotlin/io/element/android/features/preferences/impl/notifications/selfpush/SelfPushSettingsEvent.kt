/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.notifications.selfpush

import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushProtocol

sealed interface SelfPushSettingsEvent {
    /** Periodic re-read of the store-backed status (listener state, battery exemption). */
    data object RefreshStatus : SelfPushSettingsEvent

    /** Switches the current session to the built-in distributor. */
    data object UseBuiltInPush : SelfPushSettingsEvent
    data object ShowServerDialog : SelfPushSettingsEvent
    data object DismissServerDialog : SelfPushSettingsEvent
    data class SaveServer(val url: String, val readToken: String?) : SelfPushSettingsEvent
    data object ResetServer : SelfPushSettingsEvent
    data class CheckServerHealth(val url: String, val readToken: String?) : SelfPushSettingsEvent
    data object ShowProtocolDialog : SelfPushSettingsEvent
    data object DismissProtocolDialog : SelfPushSettingsEvent
    data class SetProtocol(val protocol: SelfPushProtocol) : SelfPushSettingsEvent
    data object RequestDisableBatteryOptimization : SelfPushSettingsEvent
}
