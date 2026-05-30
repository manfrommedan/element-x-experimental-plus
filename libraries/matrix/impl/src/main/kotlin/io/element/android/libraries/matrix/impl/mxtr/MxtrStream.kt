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

/**
 * One logical bidirectional stream within an [MxtrSession]. Wraps the session's
 * frame-level writes as InputStream/OutputStream so existing copy-loop code
 * works unchanged.
 */
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

    private var pendingChunk: ByteArray? = null
    private var pendingPos = 0

    fun isOpenOk(): Boolean = openOk
    fun openErrorMessage(): String? = openError

    fun awaitOpen(timeoutMs: Long): Boolean = openLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    internal fun markOpenOk() {
        openOk = true
        openLatch.countDown()
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

    override fun close() {
        // ME3-04: take the same lock as deliverData so a racing deliverData
        // never lands a chunk into `incoming` after we shut down.
        synchronized(closed) {
            if (!closed.compareAndSet(false, true)) return
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
    }
}

internal val EOF_MARKER = ByteArray(0)
