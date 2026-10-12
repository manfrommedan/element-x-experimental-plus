/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.selfpush

/** Snapshot of a live stream, for reconciling against the store. */
internal data class ActiveStreamInfo(
    val protocol: SelfPushProtocol,
    val readToken: String?,
    val topics: Set<String>,
    val alive: Boolean,
)

/** The diff between the registrations in the store and the streams currently running. */
internal data class StreamPlan(
    /** Streams to (re)open: server to the topics it must cover. */
    val toOpen: Map<String, Set<String>>,
    /** Servers whose running stream must be cancelled: stale, or superseded by a reopen. */
    val toCancel: Set<String>,
)

/**
 * Pure reconciliation for the listener service: which streams to cancel and which to open so
 * the live set matches the store. A running stream survives only when it is alive, not forced,
 * and serves exactly the current protocol, token and topic set for its server. A dead stream
 * counts as absent so the watchdog can revive it.
 */
internal fun planStreams(
    registrationsByServer: Map<String, Set<String>>,
    active: Map<String, ActiveStreamInfo>,
    protocol: SelfPushProtocol,
    readTokenFor: (String) -> String?,
    force: Boolean,
): StreamPlan {
    val toCancel = active.keys - registrationsByServer.keys
    val toOpen = mutableMapOf<String, Set<String>>()
    for ((server, topics) in registrationsByServer) {
        val readToken = readTokenFor(server)
        val current = active[server]?.takeIf { it.alive }
        val currentMatches = current != null &&
            current.protocol == protocol &&
            current.readToken == readToken &&
            current.topics == topics
        if (force || !currentMatches) {
            toOpen[server] = topics
        }
    }
    // Only live jobs need cancelling; dead ones are simply replaced.
    val cancelable = active.filterValues { it.alive }.keys
    return StreamPlan(
        toOpen = toOpen,
        toCancel = toCancel + (cancelable intersect toOpen.keys),
    )
}
