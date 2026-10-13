/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.notifications.selfpush

import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushRegistration
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushStreamStatus
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.worstSelfPushStatus
import io.element.android.libraries.ui.strings.CommonStrings

/**
 * The listener status shown on the built-in push rows, from the registrations and the live
 * stream states. Shared by the management screen and the entry row on the notification
 * settings screen so both always agree. A server without a live entry has no running stream;
 * the persisted open flags stand in so a cold start does not flash "connecting" for a healthy
 * listener.
 */
internal fun selfPushListenerStatus(
    active: Boolean,
    registrations: Map<String, SelfPushRegistration>,
    openServers: Set<String>,
    liveStatuses: Map<String, SelfPushStreamStatus>,
): SelfPushSettingsState.ListenerStatus {
    if (!active || registrations.isEmpty()) return SelfPushSettingsState.ListenerStatus.INACTIVE
    val statuses = registrations.values.map { registration ->
        liveStatuses[registration.server]
            ?: if (registration.server in openServers) SelfPushStreamStatus.CONNECTED else SelfPushStreamStatus.CONNECTING
    }
    return when (statuses.worstSelfPushStatus()) {
        SelfPushStreamStatus.CONNECTED -> SelfPushSettingsState.ListenerStatus.CONNECTED
        SelfPushStreamStatus.CONNECTING -> SelfPushSettingsState.ListenerStatus.CONNECTING
        SelfPushStreamStatus.RECONNECTING -> SelfPushSettingsState.ListenerStatus.RECONNECTING
        SelfPushStreamStatus.REFUSED -> SelfPushSettingsState.ListenerStatus.REFUSED
        SelfPushStreamStatus.NO_NETWORK -> SelfPushSettingsState.ListenerStatus.NO_NETWORK
        null -> SelfPushSettingsState.ListenerStatus.INACTIVE
    }
}

/** The string resource labeling the status on the settings rows. */
internal fun SelfPushSettingsState.ListenerStatus.labelRes(): Int = when (this) {
    SelfPushSettingsState.ListenerStatus.INACTIVE -> CommonStrings.screen_selfpush_settings_listener_status_inactive
    SelfPushSettingsState.ListenerStatus.CONNECTED -> CommonStrings.screen_selfpush_settings_listener_status_connected
    SelfPushSettingsState.ListenerStatus.CONNECTING -> CommonStrings.screen_selfpush_settings_listener_status_connecting
    SelfPushSettingsState.ListenerStatus.RECONNECTING -> CommonStrings.screen_selfpush_settings_listener_status_reconnecting
    SelfPushSettingsState.ListenerStatus.REFUSED -> CommonStrings.screen_selfpush_settings_listener_status_refused
    SelfPushSettingsState.ListenerStatus.NO_NETWORK -> CommonStrings.screen_selfpush_settings_listener_status_no_network
}
