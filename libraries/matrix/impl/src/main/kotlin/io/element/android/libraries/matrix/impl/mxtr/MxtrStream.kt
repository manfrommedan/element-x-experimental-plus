/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.mxtr

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * One logical bidirectional stream within an [MxtrSession]. Wraps the session's
 * frame-level writes as InputStream/OutputStream so existing copy-loop code
 * works unchanged.
 */
// Explicit parentheses below document the on-the-wire byte/bit layout; keep them.
@Suppress("UnnecessaryParentheses")
internal class MxtrStream(
    val id: Int,
    private val session: MxtrSession,
) : AutoCloseable {
    private val incoming = LinkedBlockingQueue<ByteArray>()
    private val openLatch = CountDownLatch(1)
    @Volatile private var openOk = false
    @Volatile private var openError: String? = null
    @Volatile private var eofReceived = false
    private val closed = AtomicBoolean(false)

    // Client->upstream send window (flow control). The server advertises the
    // initial value in OPEN_OK and tops it up with WINDOW_UPDATE frames as it
    // drains to the homeserver; outputStream.write blocks once it is exhausted,
    // back-pressuring the SDK's upload thread to the homeserver's real rate so a
    // large file streams through instead of overrunning the proxy buffer.
    // flowControlled stays false against an older server that sends no window,
    // preserving the previous unbounded-send behaviour for that case.
    private val windowLock = ReentrantLock()
    private val windowAvailable = windowLock.newCondition()
    private var sendWindow: Long = 0L
    @Volatile private var flowControlled: Boolean = false

    private var pendingChunk: ByteArray? = null
    private var pendingPos = 0

    fun isOpenOk(): Boolean = openOk
    fun openErrorMessage(): String? = openError

    fun awaitOpen(timeoutMs: Long): Boolean = openLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    internal fun markOpenOk(initialWindow: ByteArray) {
        // OPEN_OK from a flow-control-aware server carries the 4-byte initial
        // send window; an older server sends an empty payload and we stay in the
        // unbounded-send path (flowControlled = false).
        if (initialWindow.size >= 4) {
            val w = ((initialWindow[0].toLong() and 0xFFL) shl 24) or
                ((initialWindow[1].toLong() and 0xFFL) shl 16) or
                ((initialWindow[2].toLong() and 0xFFL) shl 8) or
                (initialWindow[3].toLong() and 0xFFL)
            windowLock.lock()
            try {
                sendWindow = w
                flowControlled = true
                windowAvailable.signalAll()
            } finally {
                windowLock.unlock()
            }
        }
        openOk = true
        openLatch.countDown()
    }

    /** Apply a server WINDOW_UPDATE credit and wake any writer blocked on it. */
    internal fun creditWindow(delta: ByteArray) {
        if (delta.size < 4) return
        val d = ((delta[0].toLong() and 0xFFL) shl 24) or
            ((delta[1].toLong() and 0xFFL) shl 16) or
            ((delta[2].toLong() and 0xFFL) shl 8) or
            (delta[3].toLong() and 0xFFL)
        windowLock.lock()
        try {
            sendWindow += d
            windowAvailable.signalAll()
        } finally {
            windowLock.unlock()
        }
    }

    internal fun markOpenErr(payload: ByteArray) {
        openError = if (payload.isEmpty()) "open refused" else String(payload, Charsets.UTF_8)
        openLatch.countDown()
    }

    internal fun deliverData(data: ByteArray) {
        // HI-01: drop zero-byte payloads so read() never returns 0 with len > 0.
        if (data.isEmpty()) return
        // ME3-04: synchronise the check+put against close() so a racing close
        // can't pin the chunk after the reader has shut down. close() takes
        // the same lock before setting closed=true.
        synchronized(closed) {
            if (closed.get()) return
            incoming.put(data)
        }
    }

    internal fun deliverEof() {
        eofReceived = true
        // Wake up any blocked readers.
        incoming.put(EOF_MARKER)
        // Make sure openLatch isn't left counting (e.g. peer closed before OPEN_OK).
        if (openLatch.count > 0) {
            openError = openError ?: "stream closed before open"
            openLatch.countDown()
        }
        // Wake a writer blocked on the send window: the server closed the stream
        // (e.g. wedged upstream), so the pending write must fail, not hang.
        windowLock.lock()
        try {
            windowAvailable.signalAll()
        } finally {
            windowLock.unlock()
        }
    }

    val inputStream: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else (one[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pendingChunk == null) {
                if (eofReceived && incoming.isEmpty()) return -1
                val next = try {
                    incoming.take()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return -1
                }
                if (next === EOF_MARKER) return -1
                pendingChunk = next
                pendingPos = 0
            }
            val chunk = pendingChunk!!
            val n = minOf(len, chunk.size - pendingPos)
            System.arraycopy(chunk, pendingPos, b, off, n)
            pendingPos += n
            if (pendingPos >= chunk.size) {
                pendingChunk = null
                pendingPos = 0
            }
            return n
        }

        override fun close() = this@MxtrStream.close()
    }

    val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed.get()) throw IOException("stream closed")
            var remaining = len
            var pos = off
            while (remaining > 0) {
                val chunk = minOf(remaining, MxtrSession.MAX_STREAM_PAYLOAD)
                // Flow control: block here until the server has credited room for
                // this chunk. This is what bounds the proxy's buffer to the
                // window and lets arbitrarily large files upload without loss.
                acquireSendWindow(chunk)
                val slice = b.copyOfRange(pos, pos + chunk)
                session.writeStreamFrame(id, MxtrSession.TYPE_DATA, slice)
                pos += chunk
                remaining -= chunk
            }
        }

        override fun flush() {
            // Each writeStreamFrame already flushes underlying socket.
        }

        override fun close() = this@MxtrStream.close()
    }

    // Block until the send window has room for [n] bytes, the server credits
    // us, or the stream closes. No-op when the server advertised no window
    // (older build), so behaviour against such a server is unchanged.
    private fun acquireSendWindow(n: Int) {
        if (!flowControlled) return
        windowLock.lock()
        try {
            while (sendWindow < n) {
                if (closed.get() || eofReceived) throw IOException("stream closed while awaiting send window")
                if (!windowAvailable.await(WINDOW_STALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    throw IOException("mxtr send window stalled >${WINDOW_STALL_TIMEOUT_MS}ms; upstream not draining")
                }
            }
            sendWindow -= n
        } finally {
            windowLock.unlock()
        }
    }

    override fun close() {
        // ME3-04: take the same lock as deliverData so a racing deliverData
        // never lands a chunk into `incoming` after we shut down.
        synchronized(closed) {
            if (!closed.compareAndSet(false, true)) return
        }
        // Wake a writer blocked on the send window so close() unblocks it.
        windowLock.lock()
        try {
            windowAvailable.signalAll()
        } finally {
            windowLock.unlock()
        }
        try {
            session.writeStreamFrame(id, MxtrSession.TYPE_CLOSE, EMPTY)
        } catch (_: Throwable) {
            }
        session.removeStream(id)
    }

    companion object {
        private val EOF_MARKER = ByteArray(0)
        private val EMPTY = ByteArray(0)

        // Upper bound on a single send-window stall. The server credits as it
        // drains to the homeserver, so any live-but-slow upstream keeps this
        // refreshed; only a genuinely wedged upstream (which the server also
        // signals with CLOSE) can reach it, and we then fail the write instead
        // of hanging the SDK's upload thread forever.
        private const val WINDOW_STALL_TIMEOUT_MS = 60_000L
    }
}

internal val EOF_MARKER = ByteArray(0)
