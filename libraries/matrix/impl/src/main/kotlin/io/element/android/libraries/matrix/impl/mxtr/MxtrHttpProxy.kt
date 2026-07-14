/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.mxtr

import android.content.Context
import timber.log.Timber
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException

/**
 * Local HTTP CONNECT listener that bridges matrix-rust-sdk HTTP traffic into
 * the mxtr v2 stream-multiplexed tunnel. A single long-lived [MxtrSession] to
 * the VPS carries N concurrent CONNECT-equivalent streams — amortising the
 * TLS+mxtr handshake across all matrix-rust-sdk requests.
 */
object MxtrHttpProxy {
    private val started = AtomicBoolean(false)
    private val connCounter = AtomicLong(0)
    private val activeConfig = AtomicReference<MxtrRuntimeConfig?>(null)

    @Volatile private var session: MxtrSession? = null
    private val sessionLock = Any()

    // Fixed pool — matrix-rust-sdk parallelism is well under 32 concurrent
    // CONNECT-equivalents. An unbounded newCachedThreadPool would OOM on any
    // local-app bug that opens CONNECTs without closing (LO3-08).
    private val workers = Executors.newFixedThreadPool(32) { r ->
        Thread(r, "mxtr-worker").apply { isDaemon = true }
    }

    fun start(context: Context) {
        val appCtx = context.applicationContext
        val store = MxtrPreferencesStore(appCtx)
        val config = store.snapshotBlocking()
        activeConfig.set(config)

        if (!config.enabled || config.data == null) {
            Timber.tag(TAG).i("not configured or disabled; not starting accept loop")
            MxtrStats.setAcceptLoopAlive(false)
            MxtrStats.setCurrentServer(null)
            return
        }
        MxtrStats.setCurrentServer("${config.data.host}:${config.data.port}")
        if (!started.compareAndSet(false, true)) return

        Thread(::superviseAcceptLoop, "mxtr-supervisor").apply { isDaemon = true }.start()
    }

    private fun superviseAcceptLoop() {
        var backoffMs = 1_000L
        while (true) {
            val startNanos = System.nanoTime()
            try {
                runAcceptLoop()
                MxtrStats.setAcceptLoopAlive(false)
                return
            } catch (t: Throwable) {
                MxtrStats.setAcceptLoopAlive(false)
                MxtrStats.bumpRestart()
                val upMs = (System.nanoTime() - startNanos) / 1_000_000
                Timber.tag(TAG).e(t, "accept loop died after %d ms; restart in %d ms", upMs, backoffMs)
                if (upMs > 60_000) backoffMs = 1_000L
                try {
                    Thread.sleep(backoffMs)
                } catch (_: InterruptedException) {
                    return
                }
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    private fun runAcceptLoop() {
        bindServerSocketEphemeral(MxtrConfig.LOCAL_PROXY_HOST).use { ss ->
            val boundPort = ss.localPort
            MxtrConfig.setActiveLocalPort(boundPort)
            val cfg = activeConfig.get()
            Timber.tag(TAG).i(
                "listening on %s:%d -> %s:%d (v2 stream mux)",
                MxtrConfig.LOCAL_PROXY_HOST,
                boundPort,
                cfg?.data?.host,
                cfg?.data?.port,
            )
            MxtrStats.setAcceptLoopAlive(true)
            while (true) {
                val client = ss.accept()
                workers.execute { handle(client) }
            }
        }
    }

    /**
     * Returns a live session, opening a new one if needed. Single-flight via
     * sessionLock so concurrent CONNECTs at startup don't spawn duplicate
     * TLS+handshake to the VPS.
     */
    private fun getOrCreateSession(config: MxtrRuntimeConfig): MxtrSession {
        val data = config.data ?: throw IOException("mxtr not configured")
        val cached = session
        if (cached != null && !cached.isClosed()) return cached
        synchronized(sessionLock) {
            val current = session
            if (current != null && !current.isClosed()) return current
            val fresh = MxtrSession.connect(
                serverHost = data.host,
                serverPort = data.port,
                psk = data.pskBytes(),
                sni = data.sni,
                connectTimeoutMs = 15_000,
            )
            session = fresh
            return fresh
        }
    }

    private fun handle(client: Socket) {
        val id = connCounter.incrementAndGet()
        val config = activeConfig.get() ?: run {
            try {
                client.close()
            } catch (_: Throwable) {
                }
            return
        }
        MxtrStats.connStart()
        var stream: MxtrStream? = null
        var targetHostPort = "?"
        try {
            client.tcpNoDelay = true
            // HI3-03: bound how long we wait for the CONNECT line. Without a
            // timeout, a local client that opens the socket and never writes
            // (buggy WebView, sdk reconnect storm) pins a worker thread + 1MB
            // stack indefinitely. 30s is plenty for matrix-rust-sdk.
            client.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: throw IOException("empty request")
            val parts = requestLine.split(' ')
            if (parts.size < 3 || !parts[0].equals("CONNECT", ignoreCase = true)) {
                client.getOutputStream().write("HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n".toByteArray())
                client.close()
                MxtrStats.connFailed(MxtrErrorKind.UNKNOWN, "?", "non-CONNECT request: $requestLine")
                return
            }
            targetHostPort = parts[1]
            val colon = targetHostPort.lastIndexOf(':')
            if (colon <= 0) throw IOException("bad CONNECT target $targetHostPort")
            val targetHost = targetHostPort.substring(0, colon)
            val targetPort = targetHostPort.substring(colon + 1).toInt()

            // Drain the CONNECT request headers until the blank line (or EOF).
            while (true) {
                val line = reader.readLine()
                if (line.isNullOrEmpty()) break
            }

            // One retry after a session-level failure: server may have dropped
            // the long-lived connection; fail-fast and reopen once.
            stream = openStreamWithOneRetry(config, targetHost, targetPort)

            client.getOutputStream().apply {
                write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                flush()
            }
            Timber.tag(TAG).d("[%d] stream %d -> %s", id, stream.id, targetHostPort)

            val s = stream
            val upOut = s.outputStream
            val upIn = s.inputStream
            val downOut = client.getOutputStream()
            val downIn = client.getInputStream()

            val t1 = Thread({
                copyLoop(downIn, upOut, MxtrStats::addBytesUp)
                try {
                    client.shutdownInput()
                } catch (_: Throwable) {
                    }
            }, "mxtr-up-$id").apply {
                isDaemon = true
                start()
            }
            copyLoop(upIn, downOut, MxtrStats::addBytesDown)
            try {
                t1.join(500)
            } catch (_: Throwable) {
                }
            MxtrStats.connSucceeded()
        } catch (ce: CategorizedError) {
            Timber.tag(TAG).w(ce.cause, "[%d] %s failed (%s)", id, targetHostPort, ce.kind.name)
            MxtrStats.connFailed(ce.kind, targetHostPort, ce.cause?.message.orEmpty())
            try {
                client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray())
            } catch (_: Throwable) {
                }
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "[%d] %s failed (uncategorized)", id, targetHostPort)
            MxtrStats.connFailed(MxtrErrorKind.UNKNOWN, targetHostPort, e.message.orEmpty())
            try {
                client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray())
            } catch (_: Throwable) {
                }
        } finally {
            try {
                stream?.close()
            } catch (_: Throwable) {
                }
            try {
                client.close()
            } catch (_: Throwable) {
                }
        }
    }

    private fun openStreamWithOneRetry(
        config: MxtrRuntimeConfig,
        targetHost: String,
        targetPort: Int,
    ): MxtrStream {
        for (attempt in 1..2) {
            val s = try {
                getOrCreateSession(config)
            } catch (e: UnknownHostException) {
                throw CategorizedError(MxtrErrorKind.DNS, e)
            } catch (e: SocketTimeoutException) {
                throw CategorizedError(MxtrErrorKind.TCP_TIMEOUT, e)
            } catch (e: ConnectException) {
                throw CategorizedError(MxtrErrorKind.TCP_REFUSED, e)
            } catch (e: SSLException) {
                throw CategorizedError(MxtrErrorKind.TLS_HANDSHAKE, e)
            } catch (e: IOException) {
                throw CategorizedError(MxtrErrorKind.MXTR_HANDSHAKE, e)
            }

            try {
                return s.openStream(targetHost, targetPort)
            } catch (e: TargetDialRefusedException) {
                throw CategorizedError(MxtrErrorKind.TARGET_DIAL, e)
            } catch (e: SessionClosedException) {
                if (attempt == 1) {
                    // Session died between getOrCreate and openStream. Drop it
                    // and let the next loop iteration reopen.
                    synchronized(sessionLock) {
                        if (session === s) session = null
                    }
                    continue
                }
                throw CategorizedError(MxtrErrorKind.IO, e)
            } catch (e: IOException) {
                throw CategorizedError(MxtrErrorKind.IO, e)
            }
        }
        throw CategorizedError(MxtrErrorKind.IO, IOException("openStream exhausted retries"))
    }

    private fun copyLoop(src: InputStream, dst: OutputStream, counter: (Long) -> Unit) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                dst.write(buf, 0, n)
                dst.flush()
                counter(n.toLong())
            }
        } catch (_: IOException) {
            // peer hung up - normal
        }
    }

    // Bind an OS-assigned ephemeral port on the loopback host. Passing port 0
    // lets the kernel pick a free port at random, so there is no predictable
    // port (1984/1993/...) for a local app to scan and abuse. The chosen port
    // is read back via ServerSocket.localPort at the call site and published
    // to consumers via MxtrConfig.setActiveLocalPort().
    private fun bindServerSocketEphemeral(host: String): ServerSocket {
        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getByName(host), 0))
            return ss
        } catch (e: Throwable) {
            try {
                ss.close()
            } catch (_: Throwable) {
                }
            throw IOException("could not bind local proxy on $host", e)
        }
    }

    private class CategorizedError(val kind: MxtrErrorKind, cause: Throwable) : Exception(cause)

    private const val TAG = "MxtrProxy"
}
