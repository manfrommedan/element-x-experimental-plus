/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.notifications.selfpush

import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushProtocol

/**
 * State for the built-in push management screen: everything the listener needs in one place
 * (activation, server, protocol, battery, status) instead of rows bolted onto the
 * notification settings.
 */
data class SelfPushSettingsState(
    /** True when the current distributor is the built-in listener. */
    val isActive: Boolean,
    val server: ServerState,
    val protocol: ProtocolState,
    /** True while every registered server has an open stream. */
    val listenerConnected: Boolean,
    /** True when the app is exempt from battery optimizations; drives the battery row. */
    val batteryOptimizationIgnored: Boolean,
    val eventSink: (SelfPushSettingsEvent) -> Unit,
) {
    /**
     * UI state for the push server row and its edit dialog. [healthCheck] is the result of the
     * last on-demand check, keyed by [healthCheckServer] and [healthCheckToken] so the dialog
     * can ignore results for values that are no longer in the fields. [saveFailed] is set when
     * persisting or re-registering after a server change failed; the dialog stays open and
     * shows an error.
     */
    data class ServerState(
        val customServer: String?,
        val effectiveServer: String,
        val showDialog: Boolean,
        val healthCheck: AsyncData<Unit>,
        val healthCheckServer: String?,
        val healthCheckToken: String?,
        val saveFailed: Boolean,
        /** Bearer token for servers behind auth, null when the server is open. */
        val readToken: String?,
    )

    /** UI state for the listener's protocol row and its picker dialog. */
    data class ProtocolState(
        val protocol: SelfPushProtocol,
        val showDialog: Boolean,
    )
}
