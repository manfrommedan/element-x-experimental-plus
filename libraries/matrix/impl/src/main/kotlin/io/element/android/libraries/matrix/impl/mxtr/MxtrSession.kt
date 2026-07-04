/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.mxtr

import timber.log.Timber
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket

/**
 * Long-lived mxtr v2 session. After handshake, frames carry stream-multiplexed
 * payloads — `openStream(host, port)` allocates a logical TCP-equivalent
 * stream inside this single TLS+mxtr session. Many concurrent streams share
 * one TCP+TLS+handshake, amortising the 3-4 RTT setup cost.
 *
 * Wire format inside the existing AEAD frame plaintext:
 *   [4 byte stream_id BE][1 byte type][2 byte payload_len BE][payload]
 *
 * stream_id 0 reserved for control. Client allocates odd IDs (1, 3, 5, ...).
 */
// Explicit parentheses below document the on-the-wire byte/bit layout; keep them.
@Suppress("UnnecessaryParentheses")
internal class MxtrSession private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: OutputStream,
    private val keyC2S: ByteArray,
    private val keyS2C: ByteArray,
    private val pskCfg: MxtrPskDerivedConfig,
) : AutoCloseable {
    private val nextStreamId = AtomicInteger(1)
    private val streams = ConcurrentHashMap<Int, MxtrStream>()
    private var seqRead: Long = 1
    private var seqWrite: Long = 1
    private val closed = AtomicBoolean(false)
    private val lastWriteAtNanos = AtomicLong(System.nanoTime())
    @Volatile private var heartbeatThread: Thread? = null

    // CR-W1: every socket write funnels through one dedicated writer thread.
    // Producers (reader PONG, heartbeat PING, each stream's upload) only enqueue
    // here and never touch the socket, so a bulk upload blocking on a
    // backpressured output.write can no longer hold a lock the reader needs to
    // answer a PING. Previously reader + heartbeat + uploads shared one writeLock
    // around a blocking write; a slow upload stalled the reader, which then could
    // not process WINDOW_UPDATE / DATA, wedging the whole session until the app
    // was restarted. With a single writer the reader's only blocking point is the
    // soTimeout-bounded socket read, so the session can no longer freeze
    // permanently. The queue stays bounded in practice by per-stream flow
    // control: an upload thread blocks in acquireSendWindow after enqueuing one
    // window of DATA, so a stalled socket holds at most ~one window per stream.
    private val writeQueue = LinkedBlockingQueue<ByteArray>()
    private val writerPoison = ByteArray(0)
    @Volatile private var writerThread: Thread? = null

    init {
        // The writer owns the socket output stream; start it before any producer
        // (heartbeat / reader / openStream) can enqueue a frame.
        val wt = Thread(::writerLoop, "mxtr-session-writer").apply { isDaemon = true }
        writerThread = wt
        wt.start()
        // WR4-01: start heartbeat BEFORE reader. If we started reader first
        // and the reader hit EOF on its first frame, close() would fire while
        // heartbeatThread was still null and the interrupt would be a silent
        // no-op. By starting heartbeat first (and assigning the field before
        // its start), any subsequent close() always sees a non-null thread
        // to interrupt.
        val ht = Thread(::heartbeatLoop, "mxtr-session-heartbeat").apply { isDaemon = true }
        heartbeatThread = ht
        ht.start()
        Thread(::readerLoop, "mxtr-session-reader").apply {
            isDaemon = true
            start()
        }
    }

    fun isClosed(): Boolean = closed.get()

    fun openStream(targetHost: String, targetPort: Int, openTimeoutMs: Long = 15_000): MxtrStream {
        if (closed.get()) throw SessionClosedException()
        val sid = nextStreamId.getAndAdd(2)
        // IN4-02: defensive — if a long-lived session ever burns 2^31 IDs we
        // wrap into negative and collide with live odd-IDs. Force the caller
        // to reopen the session instead of silently corrupting frames.
        if (sid <= 0) throw IOException("stream id space exhausted; reopen session")
        val stream = MxtrStream(sid, this)
        streams[sid] = stream

        val hostBytes = targetHost.toByteArray()
        require(hostBytes.size <= 255) { "target host too long" }
        val targetSpec = ByteArray(1 + 1 + hostBytes.size + 2)
        targetSpec[0] = 2 // domain addr type
        targetSpec[1] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, targetSpec, 2, hostBytes.size)
        targetSpec[2 + hostBytes.size] = (targetPort ushr 8).toByte()
        targetSpec[3 + hostBytes.size] = targetPort.toByte()

        try {
            writeStreamFrame(sid, TYPE_OPEN, targetSpec)
        } catch (e: IOException) {
            streams.remove(sid)
            throw e
        }

        val opened = try {
            stream.awaitOpen(openTimeoutMs)
        } catch (ie: InterruptedException) {
            // HI3-02: thread interrupt at the await must not leak the streams
            // map entry or the server-side upstream socket. Mark interrupted
            // so caller can unwind, send CLOSE to the server, and rethrow.
            streams.remove(sid)
            sendCloseBestEffort(sid)
            Thread.currentThread().interrupt()
            throw IOException("openStream interrupted", ie)
        }
        if (!opened) {
            streams.remove(sid)
            sendCloseBestEffort(sid)
            throw IOException("stream open timed out after ${openTimeoutMs}ms")
        }
        if (!stream.isOpenOk()) {
            streams.remove(sid)
            throw TargetDialRefusedException("server refused stream open: ${stream.openErrorMessage()}")
        }
        return stream
    }

    // WR4-02: best-effort CLOSE for cleanup paths (timeout / interrupt). If the
    // session is already closed there is nothing to flush and writeStreamFrame
    // would just throw SessionClosedException, so skip silently.
    private fun sendCloseBestEffort(sid: Int) {
        if (closed.get()) return
        try {
            writeStreamFrame(sid, TYPE_CLOSE, ByteArray(0))
        } catch (_: IOException) {
            }
    }

    internal fun writeStreamFrame(sid: Int, type: Byte, payload: ByteArray) {
        if (payload.size > MAX_STREAM_PAYLOAD) {
            // Split into chunks at caller level for DATA; OPEN/CLOSE/PING fit fine.
            throw IOException("frame payload too large: ${payload.size}")
        }
        val inner = ByteArray(FRAME_HEADER + payload.size)
        inner[0] = (sid ushr 24).toByte()
        inner[1] = (sid ushr 16).toByte()
        inner[2] = (sid ushr 8).toByte()
        inner[3] = sid.toByte()
        inner[4] = type
        inner[5] = (payload.size ushr 8).toByte()
        inner[6] = payload.size.toByte()
        System.arraycopy(payload, 0, inner, FRAME_HEADER, payload.size)

        // Hand the framed plaintext to the single writer thread (CR-W1). We never
        // touch the socket here, so this returns without ever blocking on a slow
        // or backpressured write — which is what keeps the reader's PONG path and
        // WINDOW_UPDATE processing alive during a large upload. A real socket error
        // surfaces asynchronously: the writer tears the session down and callers
        // observe it as SessionClosedException / stream EOF on their next op.
        if (closed.get()) throw SessionClosedException()
        writeQueue.offer(inner)
    }

    // Single owner of the socket output stream and seqWrite. Drains framed
    // plaintexts enqueued by writeStreamFrame and writes them in FIFO order, so
    // per-stream frame order is preserved without any lock guarding output /
    // seqWrite. A write failure (peer gone / NAT teardown) or the poison marker
    // enqueued by close() ends the loop and tears the session down so the caller
    // reconnects.
    private fun writerLoop() {
        try {
            while (true) {
                val inner = writeQueue.take()
                if (inner === writerPoison || closed.get()) return
                writePaddedAead(output, keyC2S, seqWrite, inner)
                seqWrite++
                lastWriteAtNanos.set(System.nanoTime())
            }
        } catch (_: InterruptedException) {
            // closed
        } catch (e: Throwable) {
            if (!closed.get()) Timber.tag(TAG).w(e, "writer loop ended")
        } finally {
            close()
        }
    }

    // PSK-derived idle-padding heartbeat: interval, padding size, and idle
    // threshold all come from HKDF(PSK) so two deployments with different PSKs
    // breathe on different cadences. Symmetric with the Go server's
    // heartbeatLoop in cmd/mxtr-server/session_v2.go.
    private fun heartbeatLoop() {
        // Both ends use inclusive ranges (server uses mrand.IntN(range+1)).
        // Keep parity so the documented symmetry claim holds (IN-02).
        val rangeMs = (pskCfg.heartbeatMaxMs - pskCfg.heartbeatMinMs).coerceAtLeast(0)
        val padRange = (pskCfg.heartbeatPadMax - pskCfg.heartbeatPadMin).coerceAtLeast(0)
        try {
            while (!closed.get()) {
                val sleepMs = pskCfg.heartbeatMinMs.toLong() + RNG.nextInt(rangeMs + 1)
                Thread.sleep(sleepMs)
                if (closed.get()) return
                val idleMs = (System.nanoTime() - lastWriteAtNanos.get()) / 1_000_000
                if (idleMs < pskCfg.idleThresholdMs) continue
                val padSize = pskCfg.heartbeatPadMin + RNG.nextInt(padRange + 1)
                val pad = ByteArray(padSize)
                RNG.nextBytes(pad)
                try {
                    writeStreamFrame(0, TYPE_PING, pad)
                } catch (_: IOException) {
                    return
                }
            }
        } catch (_: InterruptedException) {
            // closed
        }
    }

    private fun readerLoop() {
        try {
            while (!closed.get()) {
                val pt = readPaddedAead(input, keyS2C, seqRead)
                seqRead++
                if (pt.size < FRAME_HEADER) {
                    Timber.tag(TAG).w("short frame ${pt.size}")
                    return
                }
                val sid = ((pt[0].toInt() and 0xFF) shl 24) or
                    ((pt[1].toInt() and 0xFF) shl 16) or
                    ((pt[2].toInt() and 0xFF) shl 8) or
                    (pt[3].toInt() and 0xFF)
                val type = pt[4]
                val payloadLen = ((pt[5].toInt() and 0xFF) shl 8) or (pt[6].toInt() and 0xFF)
                if (payloadLen > pt.size - FRAME_HEADER) {
                    Timber.tag(TAG).w("payload len $payloadLen exceeds frame ${pt.size}")
                    return
                }
                val payload = pt.copyOfRange(FRAME_HEADER, FRAME_HEADER + payloadLen)

                when (type) {
                    TYPE_OPEN_OK -> streams[sid]?.markOpenOk(payload)
                    TYPE_OPEN_ERR -> streams[sid]?.markOpenErr(payload)
                    TYPE_DATA -> streams[sid]?.deliverData(payload)
                    TYPE_WINDOW_UPDATE -> streams[sid]?.creditWindow(payload)
                    TYPE_CLOSE -> {
                        val st = streams.remove(sid)
                        st?.deliverEof()
                    }
                    TYPE_PING -> {
                        try {
                            writeStreamFrame(0, TYPE_PONG, payload)
                        } catch (_: IOException) {
                            // peer gone
                        }
                    }
                    TYPE_PONG -> Unit
                    else -> Timber.tag(TAG).w("unknown frame type 0x${type.toString(16)} stream $sid")
                }
            }
        } catch (e: Throwable) {
            if (!closed.get()) Timber.tag(TAG).w(e, "reader loop ended")
        } finally {
            close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // ME3-05: interrupt heartbeat thread so close() unblocks it within one
        // catch (InterruptedException) instead of up to heartbeatMaxMs (~70s).
        heartbeatThread?.interrupt()
        // Stop the writer: drop anything still queued and wake a writer parked in
        // take() with the poison marker; socket.close() below unblocks a writer
        // parked in a backpressured output.write.
        writeQueue.clear()
        writeQueue.offer(writerPoison)
        writerThread?.interrupt()
        for (st in streams.values) st.deliverEof()
        streams.clear()
        try {
            socket.close()
        } catch (_: Throwable) {
            }
    }

    private fun writePaddedAead(out: OutputStream, key: ByteArray, seq: Long, plaintext: ByteArray) {
        val padSize = pickPadRung(plaintext.size + 2)
        val padded = ByteArray(padSize)
        padded[0] = (plaintext.size ushr 8).toByte()
        padded[1] = plaintext.size.toByte()
        System.arraycopy(plaintext, 0, padded, 2, plaintext.size)
        val rndPad = ByteArray(padSize - 2 - plaintext.size)
        if (rndPad.isNotEmpty()) {
            RNG.nextBytes(rndPad)
            System.arraycopy(rndPad, 0, padded, 2 + plaintext.size, rndPad.size)
        }
        val nonce = MxtrCrypto.frameNonce(seq)
        val ct = MxtrCrypto.chacha20Poly1305Encrypt(key, nonce, padded)
        val lenBuf = ByteArray(2)
        lenBuf[0] = (ct.size ushr 8).toByte()
        lenBuf[1] = ct.size.toByte()
        out.write(lenBuf)
        out.write(ct)
        out.flush()
    }

    private fun readPaddedAead(input: DataInputStream, key: ByteArray, seq: Long): ByteArray {
        val lenHi = input.read()
        if (lenHi < 0) throw IOException("eof reading frame length")
        val lenLo = input.read()
        if (lenLo < 0) throw IOException("eof reading frame length")
        val ctLen = (lenHi shl 8) or lenLo
        if (ctLen <= 0 || ctLen > MAX_CIPHERTEXT) throw IOException("bad frame length $ctLen")
        val ct = ByteArray(ctLen)
        input.readFully(ct)
        val nonce = MxtrCrypto.frameNonce(seq)
        val padded = MxtrCrypto.chacha20Poly1305Decrypt(key, nonce, ct)
        if (padded.size < 2) throw IOException("inner frame too small")
        val realLen = ((padded[0].toInt() and 0xFF) shl 8) or (padded[1].toInt() and 0xFF)
        if (realLen > padded.size - 2) throw IOException("real_len $realLen > inner ${padded.size - 2}")
        return padded.copyOfRange(2, 2 + realLen)
    }

    internal fun removeStream(sid: Int) {
        streams.remove(sid)
    }

    companion object {
        const val TAG = "MxtrSession"

        const val TYPE_OPEN: Byte = 0x01
        const val TYPE_DATA: Byte = 0x02
        const val TYPE_CLOSE: Byte = 0x03
        const val TYPE_PING: Byte = 0x04
        const val TYPE_PONG: Byte = 0x05
        const val TYPE_OPEN_OK: Byte = 0x06
        const val TYPE_OPEN_ERR: Byte = 0x07
        const val TYPE_WINDOW_UPDATE: Byte = 0x08

        const val FRAME_HEADER = 7

        private const val NONCE_LEN = 16
        private const val MAC_LEN = 16
        private const val MAX_HS_PAD = 255
        private const val MAX_PLAINTEXT = 16_384 - 2
        const val MAX_STREAM_PAYLOAD = MAX_PLAINTEXT - FRAME_HEADER
        private const val MAX_PADDED = 16_384
        private const val MAX_CIPHERTEXT = MAX_PADDED + 16

        // PADME-style ladder symmetric with the Go server's padSizes. 13 rungs
        // with 1.5x half-steps blur the size histogram across more buckets,
        // and pickPadRung picks the next rung up with a size-scaled
        // probability so small signaling frames blend bucket counts where it
        // matters most. Both ends apply this independently — the wire only
        // ever sees the encrypted ciphertext length, so client and server
        // need not synchronise rung choice.
        private val PAD_SIZES = intArrayOf(256, 384, 512, 768, 1024, 1536, 2048, 3072, 4096, 6144, 8192, 12_288, 16_384)
        private val RNG = SecureRandom()

        private fun nextPadSize(n: Int): Int {
            for (s in PAD_SIZES) if (s >= n) return s
            return PAD_SIZES.last()
        }

        private fun bumpProbability(minSize: Int): Int = when {
            minSize < 1024 -> 30
            minSize < 4096 -> 18
            else -> 8
        }

        private fun pickPadRung(minSize: Int): Int {
            val base = nextPadSize(minSize)
            if (RNG.nextInt(100) >= bumpProbability(minSize)) return base
            for (i in PAD_SIZES.indices) {
                if (PAD_SIZES[i] == base && i + 1 < PAD_SIZES.size) return PAD_SIZES[i + 1]
            }
            return base
        }

        fun connect(
            serverHost: String,
            serverPort: Int,
            psk: ByteArray,
            sni: String? = null,
            connectTimeoutMs: Int = 10_000,
        ): MxtrSession {
            // HI-02: every early-return throw path must close `raw`, otherwise
            // a connect-time failure during reconnect storms leaks one FD per
            // retry. The success path transfers ownership to the MxtrSession.
            // Derive once: used for soTimeout (ME3-01) and the session below.
            val pskCfg = MxtrPskDerivedConfig.derive(psk)
            val raw = Socket()
            var ownsSocket = true
            try {
                raw.tcpNoDelay = true
                // InetSocketAddress(literalIp, port) does NOT trigger DNS when
                // the host string is a numeric literal — verified at parse time
                // by MxtrShareString.parse refusing hostnames. createSocket
                // below is then layered onto the already-connected raw socket
                // with serverName=sni so the SNI extension in ClientHello is
                // the persisted CDN hostname, not the IP.
                raw.connect(InetSocketAddress(serverHost, serverPort), connectTimeoutMs)

                val sslFactory = MxtrCrypto.insecureSslSocketFactory()
                // SNI host: prefer the explicit value from the share-string,
                // fall back to the IP literal. Falling back to IP makes JSSE
                // emit no SNI (TLS forbids IP-literal SNI) which is itself a
                // tell — operators should always provide -sni on the server.
                val sniHost = if (!sni.isNullOrEmpty()) sni else serverHost
                val tls = sslFactory.createSocket(raw, sniHost, serverPort, true) as SSLSocket
                tls.enabledProtocols = arrayOf("TLSv1.3")
                tls.startHandshake()
                // ME3-01: bound silent NAT-teardown detection. A 3G/4G drop
                // with no FIN leaves the reader blocked on the SSL input until
                // the OS keepalive fires (~2h). Heartbeat would catch it via
                // write failure, but if Doze paused the heartbeat thread the
                // reader hangs. soTimeout caps the wait at heartbeatMaxMs +
                // 30s so the reader bails out cleanly under any circumstance.
                tls.soTimeout = pskCfg.heartbeatMaxMs + 30_000

                val input = DataInputStream(tls.inputStream)
                val output = tls.outputStream

                val nonceC = ByteArray(NONCE_LEN)
                RNG.nextBytes(nonceC)
                val padLen = RNG.nextInt(MAX_HS_PAD + 1)
                val pad = ByteArray(padLen)
                RNG.nextBytes(pad)
                val padLenByte = byteArrayOf(padLen.toByte())
                val mac = MxtrCrypto.hmacSha256(psk, nonceC, padLenByte, pad, "c2s-hs".toByteArray())
                    .copyOfRange(0, MAC_LEN)
                output.write(nonceC + padLenByte + pad + mac)
                output.flush()

                val first = ByteArray(NONCE_LEN + 1)
                input.readFully(first)
                val nonceS = first.copyOfRange(0, NONCE_LEN)
                val srvPadLen = first[NONCE_LEN].toInt() and 0xFF
                // LO3-05: cap pad length defensively even though byte is 0-255.
                // Mirrors the server's maxHandshakePad invariant.
                if (srvPadLen > MAX_HS_PAD) throw IOException("server handshake pad too large: $srvPadLen")
                val rest = ByteArray(srvPadLen + MAC_LEN)
                input.readFully(rest)
                val srvPad = rest.copyOfRange(0, srvPadLen)
                val srvMacGot = rest.copyOfRange(srvPadLen, srvPadLen + MAC_LEN)
                val srvMacWant = MxtrCrypto.hmacSha256(
                    psk,
                    nonceS,
                    byteArrayOf(srvPadLen.toByte()),
                    srvPad,
                    "s2c-hs".toByteArray()
                ).copyOfRange(0, MAC_LEN)
                // HI-03: constant-time MAC compare. Non-constant-time contentEquals
                // would leak the position of the first differing byte via timing.
                if (!constantTimeEquals(srvMacWant, srvMacGot)) {
                    throw IOException("mxtr server hello mac mismatch")
                }

                val salt = nonceC + nonceS
                val keyC2S = MxtrCrypto.hkdfSha256(psk, salt, "c2s-key".toByteArray(), 32)
                val keyS2C = MxtrCrypto.hkdfSha256(psk, salt, "s2c-key".toByteArray(), 32)

                // ME3-02: flip the ownership flag only AFTER the session
                // constructor returns. If MxtrSession's init {} throws (e.g.
                // Thread.start failure), ownsSocket stays true and the finally
                // closes raw.
                val sess = MxtrSession(raw, input, output, keyC2S, keyS2C, pskCfg)
                ownsSocket = false
                return sess
            } finally {
                if (ownsSocket) {
                    try {
                    raw.close()
                } catch (_: Throwable) {
                    }
                }
            }
        }

        private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
            if (a.size != b.size) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
            return diff == 0
        }
    }
}

/** Thrown by writeStreamFrame when the session has already shut down. */
internal class SessionClosedException : IOException("mxtr session closed")

/** Thrown when the server replied OPEN_ERR for a stream open request. */
internal class TargetDialRefusedException(message: String) : IOException(message)
