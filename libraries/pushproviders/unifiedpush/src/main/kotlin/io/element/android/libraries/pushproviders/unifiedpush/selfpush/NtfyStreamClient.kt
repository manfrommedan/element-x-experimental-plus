/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.selfpush

import dev.zacsweers.metro.Inject
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.log.logger.LoggerTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val loggerTag = LoggerTag("NtfyStreamClient", LoggerTag.PushLoggerTag)

/** A pushed message off the stream: its server-side id, arrival time, topic and the full POST body it carried. */
data class NtfyMessage(
    val id: String,
    val topic: String,
    val content: String,
    val time: Long,
)

/** The server refused the stream (auth, not found): retrying would loop forever. */
class FatalStreamException(message: String) : Exception(message)

/**
 * Holds a streaming connection to an ntfy server and yields the pushes arriving on it.
 *
 * Both ntfy protocols speak the same JSON frames (`open`, `keepalive`, `message`), one frame
 * per line over `GET /{topics}/json` or one frame per message over `/{topics}/ws`. Only
 * `message` frames carry a push; the rest double as proof the connection is alive. The HTTP
 * protocol encodes that as a read timeout just past two default 45s keepalive intervals; the
 * WebSocket protocol cannot use read timeouts (OkHttp manages the upgraded socket), so a
 * monitor closes any socket silent for longer than [Liveness.maxSilence]. Either way the flow
 * fails and the caller reconnects with a bounded backoff.
 *
 * [since] is read fresh on every (re)connect and forwarded as the `since` query parameter, so
 * a reconnect replays whatever the server cached while the stream was down instead of dropping
 * those pushes. Replays overlap with what was already seen; the caller dedupes by message id.
 */
class NtfyStreamClient @Inject constructor(
    okHttpClient: OkHttpClient,
    private val clock: BackoffClock = BackoffClock(),
    private val liveness: Liveness = Liveness(),
) {
    /** Frame silence thresholds, overridable in tests so they do not wait in real time. */
    data class Liveness(
        val maxSilence: Duration = 95.seconds,
        val checkInterval: Duration = 15.seconds,
    )

    // No client-side pings: the server's keepalive frames already warm the NAT binding and
    // feed the silence monitor, and an extra ping every 30s would double the radio wakeups.
    private val webSocketClient = okHttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    private val httpStreamClient = okHttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(liveness.maxSilence.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    fun stream(
        server: String,
        topics: Set<String>,
        readToken: String?,
        protocol: SelfPushProtocol,
        onOpen: () -> Unit = {},
        since: () -> Long?,
    ): Flow<NtfyMessage> {
        require(topics.isNotEmpty()) { "No topics to subscribe to" }
        return readFrames(server, topics, readToken, protocol, since, onOpen).retryWhen { cause, attempt ->
            // Never retry a cancelled collection: the buffered test clocks do not suspend,
            // so without this a cancel during a broken stream would spin reconnects forever.
            if (cause is FatalStreamException || cause is CancellationException) throw cause
            currentCoroutineContext().ensureActive()
            val backoff = clock.backoff(attempt)
            Timber.tag(loggerTag.value).w("Stream broke (${cause.message}), reconnecting in $backoff")
            clock.delay(backoff)
            true
        }.flowOn(Dispatchers.IO)
    }

    private fun readFrames(
        server: String,
        topics: Set<String>,
        readToken: String?,
        protocol: SelfPushProtocol,
        since: () -> Long?,
        onOpen: () -> Unit,
    ): Flow<NtfyMessage> {
        // The request is built inside the flow so every reconnect re-reads `since` and gets a
        // fresh catch-up window, instead of replaying the first attempt's forever.
        return callbackFlow {
            val request = buildRequest(server, topics, protocol, readToken, since())
            when (protocol) {
                SelfPushProtocol.WEBSOCKET -> readWebSocketFrames(server, topics, request, onOpen)
                SelfPushProtocol.JSON_STREAM -> readHttpFrames(server, topics, request, onOpen)
            }
        }
    }

    private fun buildRequest(
        server: String,
        topics: Set<String>,
        protocol: SelfPushProtocol,
        readToken: String?,
        since: Long?,
    ): Request {
        return Request.Builder().url(streamUrl(server, topics, protocol, since)).apply {
            if (readToken != null) header("Authorization", "Bearer $readToken")
        }.build()
    }

    private fun streamUrl(server: String, topics: Set<String>, protocol: SelfPushProtocol, since: Long?): String {
        val suffix = when (protocol) {
            SelfPushProtocol.WEBSOCKET -> "ws"
            SelfPushProtocol.JSON_STREAM -> "json"
        }
        return buildString {
            append(server.trimEnd('/'))
            append('/')
            append(topics.joinToString(","))
            append('/')
            append(suffix)
            if (since != null) append("?since=").append(since)
        }
    }

    private suspend fun kotlinx.coroutines.channels.ProducerScope<NtfyMessage>.readHttpFrames(
        server: String,
        topics: Set<String>,
        request: Request,
        onOpen: () -> Unit,
    ) {
        val call = httpStreamClient.newCall(request)
        // The blocking read ignores coroutine cancellation on its own: awaitClose cancels the
        // call, the socket dies under the reader and its IOException fails the flow cleanly.
        // IO errors leave through close(cause) so they end the flow as ordinary stream
        // failures; anything else thrown here fails the producer scope, which retryWhen
        // also sees. Cancellation never reaches this catch: it stops the channel first.
        val reader = launch(Dispatchers.IO) {
            try {
                val response = call.execute()
                if (!response.isSuccessful) {
                    response.close()
                    close(refusalOrFailure(response.code))
                    return@launch
                }

                val source = response.body.source()
                Timber.tag(loggerTag.value).d("HTTP stream open for ${topics.size} topic(s) on $server")
                onOpen()
                while (true) {
                    val line = source.readUtf8Line() ?: throw IOException("Stream closed by the server")
                    parseFrame(line)?.let { trySendBlocking(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                close(e)
            }
        }
        try {
            awaitClose { call.cancel() }
        } finally {
            reader.cancel()
        }
    }

    private suspend fun kotlinx.coroutines.channels.ProducerScope<NtfyMessage>.readWebSocketFrames(
        server: String,
        topics: Set<String>,
        request: Request,
        onOpen: () -> Unit,
    ) {
        val lastFrameAt = AtomicLong(System.currentTimeMillis())
        val webSocket = webSocketClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                lastFrameAt.set(System.currentTimeMillis())
                Timber.tag(loggerTag.value).d("WebSocket stream open for ${topics.size} topic(s) on $server")
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastFrameAt.set(System.currentTimeMillis())
                // Backpressure, not drops: catch-up replays can outrun the collector,
                // and those are exactly the pushes since= exists to save.
                parseFrame(text)?.let { trySendBlocking(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // OkHttp does not answer a server-initiated close on its own: without this
                // reply the socket hangs in the closing state and onClosed never fires.
                webSocket.close(NORMAL_CLOSURE_STATUS, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close(IOException("Stream closed by the server"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(t.toStreamException(response))
            }
        })

        val monitor = launch {
            while (isActive) {
                delay(liveness.checkInterval)
                val silence = System.currentTimeMillis() - lastFrameAt.get()
                if (silence > liveness.maxSilence.inWholeMilliseconds) {
                    Timber.tag(loggerTag.value).w("No frame for $silence ms, reconnecting")
                    webSocket.close(NORMAL_CLOSURE_STATUS, "inactive")
                    break
                }
            }
        }

        awaitClose {
            monitor.cancel()
            webSocket.close(NORMAL_CLOSURE_STATUS, null)
        }
    }

    private fun Throwable.toStreamException(response: Response?): Exception {
        return when {
            response == null -> IOException("Stream failed", this)
            else -> refusalOrFailure(response.code)
        }
    }

    private fun refusalOrFailure(code: Int): Exception {
        // 429 is the server asking to slow down, not a refusal: retry with the usual backoff.
        return if (code in 400..499 && code != 429) {
            FatalStreamException("Stream refused by the server: HTTP $code")
        } else {
            IOException("Stream failed: HTTP $code")
        }
    }

    private fun parseFrame(text: String): NtfyMessage? {
        if (text.isBlank()) return null
        val frame = runCatchingExceptions { json.decodeFromString<NtfyFrame>(text) }
            .onFailure { Timber.tag(loggerTag.value).w("Skipping an unreadable frame") }
            .getOrNull()
            ?: return null
        return frame.toMessage()
    }

    private fun NtfyFrame.toMessage(): NtfyMessage? {
        if (event != "message") return null
        val id = id ?: return null
        val time = time ?: return null
        val topic = topic ?: return null
        val message = message ?: return null
        return NtfyMessage(id = id, topic = topic, content = message, time = time)
    }

    @Serializable
    private data class NtfyFrame(
        val event: String,
        val id: String? = null,
        val time: Long? = null,
        val topic: String? = null,
        val message: String? = null,
    )

    /** Reconnect pacing, overridable in tests so they do not sleep in real time. */
    open class BackoffClock(
        private val random: Random = Random,
    ) {
        open fun backoff(attempt: Long): Duration {
            val base = minOf(1L shl attempt.toInt().coerceAtMost(4), BACKOFF_CAP_S).seconds
            return base * random.nextDouble(0.8, 1.2)
        }

        open suspend fun delay(duration: Duration) {
            kotlinx.coroutines.delay(duration)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_S = 15L
        const val BACKOFF_CAP_S = 15L
        const val NORMAL_CLOSURE_STATUS = 1000
    }
}
