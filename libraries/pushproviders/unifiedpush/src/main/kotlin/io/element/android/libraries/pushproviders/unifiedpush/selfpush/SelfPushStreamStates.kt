/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.selfpush

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Live health of the listener's stream to one notification server. */
enum class SelfPushStreamStatus {
    /** Handshake in flight, no verdict yet. */
    CONNECTING,

    /** Stream open, frames flowing. */
    CONNECTED,

    /** The stream broke and a reconnect is backing off. */
    RECONNECTING,

    /** The server refused the stream (auth, ACL); reconnecting alone will not heal it. */
    REFUSED,

    /** No network route, the streams are parked until one comes back. */
    NO_NETWORK,
}

/**
 * The single status to surface for a set of per-server statuses: the one that most needs the
 * user's attention wins, so a single refused server marks the whole listener refused. A fully
 * parked listener (every stream without a network route) reports [SelfPushStreamStatus.NO_NETWORK]
 * instead of a misleading reconnect. Null when there is nothing to aggregate.
 */
fun Collection<SelfPushStreamStatus>.worstSelfPushStatus(): SelfPushStreamStatus? {
    if (isEmpty()) return null
    if (all { it == SelfPushStreamStatus.NO_NETWORK }) return SelfPushStreamStatus.NO_NETWORK
    return listOf(
        SelfPushStreamStatus.REFUSED,
        SelfPushStreamStatus.RECONNECTING,
        SelfPushStreamStatus.CONNECTING,
        SelfPushStreamStatus.CONNECTED,
        SelfPushStreamStatus.NO_NETWORK,
    ).firstOrNull { it in this }
}

/**
 * Process-wide live view of the listener's per-server stream health. The subscriber service
 * writes it as connections open, break and heal; the settings screen collects it so the status
 * row updates without waiting for a resume refresh. Empty while the listener is not running.
 */
@Inject
@SingleIn(AppScope::class)
class SelfPushStreamStates {
    private val mutableStates = MutableStateFlow<Map<String, SelfPushStreamStatus>>(emptyMap())

    /** Per-server statuses, keyed by the server URL. */
    val states: StateFlow<Map<String, SelfPushStreamStatus>> = mutableStates

    fun set(server: String, status: SelfPushStreamStatus) {
        mutableStates.update { it + (server to status) }
    }

    fun remove(server: String) {
        mutableStates.update { it - server }
    }
}
