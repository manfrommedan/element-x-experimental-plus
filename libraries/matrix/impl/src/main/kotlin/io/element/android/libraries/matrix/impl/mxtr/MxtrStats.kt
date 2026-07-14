/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.mxtr

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class MxtrErrorKind {
    DNS, // upstream host DNS failed
    TCP_REFUSED, // TCP connect to VPS refused
    TCP_TIMEOUT, // TCP connect timed out
    TLS_HANDSHAKE, // outer TLS handshake failed
    MXTR_HANDSHAKE, // inner mxtr HMAC mismatch / handshake protocol err
    TARGET_DIAL, // VPS could not dial the inner target
    IO, // generic I/O after established
    UNKNOWN, // catch-all
}

data class MxtrErrorEvent(
    val ts: Long,
    val kind: MxtrErrorKind,
    val target: String,
    val message: String,
)

data class MxtrStatsSnapshot(
    val acceptLoopAlive: Boolean,
    val acceptLoopRestarts: Int,
    val active: Int,
    val total: Long,
    val succeeded: Long,
    val failed: Long,
    val bytesUp: Long,
    val bytesDown: Long,
    val errorsByKind: Map<MxtrErrorKind, Long>,
    val recentErrors: List<MxtrErrorEvent>,
    val currentServer: String?,
)

object MxtrStats {
    private val active = AtomicInteger(0)
    private val total = AtomicLong(0)
    private val succeeded = AtomicLong(0)
    private val failed = AtomicLong(0)
    private val bytesUp = AtomicLong(0)
    private val bytesDown = AtomicLong(0)
    private val acceptLoopRestarts = AtomicInteger(0)
    private val acceptLoopAlive = AtomicReference(false)
    private val currentServer = AtomicReference<String?>(null)

    private val errorsByKind = MxtrErrorKind.entries.associateWith { AtomicLong(0) }

    private val recentErrors = ArrayDeque<MxtrErrorEvent>()
    private val recentErrorsLock = Object()
    private const val RECENT_MAX = 32

    internal fun connStart() {
        active.incrementAndGet()
        total.incrementAndGet()
    }

    internal fun connSucceeded() {
        active.decrementAndGet()
        succeeded.incrementAndGet()
    }

    internal fun connFailed(kind: MxtrErrorKind, target: String, message: String) {
        active.decrementAndGet()
        failed.incrementAndGet()
        errorsByKind[kind]?.incrementAndGet()
        synchronized(recentErrorsLock) {
            recentErrors.addFirst(MxtrErrorEvent(System.currentTimeMillis(), kind, target, message.take(200)))
            while (recentErrors.size > RECENT_MAX) recentErrors.removeLast()
        }
    }

    internal fun addBytesUp(n: Long) {
        bytesUp.addAndGet(n)
    }
    internal fun addBytesDown(n: Long) {
        bytesDown.addAndGet(n)
    }

    internal fun setAcceptLoopAlive(alive: Boolean) = acceptLoopAlive.set(alive)
    internal fun bumpRestart() = acceptLoopRestarts.incrementAndGet()
    internal fun setCurrentServer(s: String?) = currentServer.set(s)

    fun snapshot(): MxtrStatsSnapshot {
        val errors = synchronized(recentErrorsLock) { recentErrors.toList() }
        return MxtrStatsSnapshot(
            acceptLoopAlive = acceptLoopAlive.get(),
            acceptLoopRestarts = acceptLoopRestarts.get(),
            active = active.get(),
            total = total.get(),
            succeeded = succeeded.get(),
            failed = failed.get(),
            bytesUp = bytesUp.get(),
            bytesDown = bytesDown.get(),
            errorsByKind = errorsByKind.mapValues { it.value.get() },
            recentErrors = errors,
            currentServer = currentServer.get(),
        )
    }
}
