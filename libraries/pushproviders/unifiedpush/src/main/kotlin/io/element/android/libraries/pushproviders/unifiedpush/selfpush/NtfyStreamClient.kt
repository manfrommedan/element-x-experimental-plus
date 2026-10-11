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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val loggerTag = LoggerTag("NtfyStreamClient", LoggerTag.PushLoggerTag)

/** A pushed message off the stream: the topic it arrived on and the full POST body it carried. */
data class NtfyMessage(
    val topic: String,
    val content: String,
)

/** The server refused the stream (auth, not found): retrying would loop forever. */
class FatalStreamException(message: String) : Exception(message)

/**
 * Holds a streaming connection to an ntfy server and yields the pushes arriving on it.
 *
 * ntfy serves `GET /{topic1,topic2}/json` as an NDJSON stream: one JSON object per line,
 * `event` being `open`, `keepalive` or `message`. Only `message` frames carry a push; the
 * rest double as proof the connection is alive, which is why the read timeout is set just
 * past two keepalive intervals: a socket silent for that long is dead regardless of what
 * the OS thinks, and the flow fails so the caller reconnects.
 */
class NtfyStreamClient @Inject constructor(
    okHttpClient: OkHttpClient,
    private val clock: BackoffClock = BackoffClock(),
) {
    private val streamClient = okHttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        // Just past two default ntfy keepalive intervals (45s each): doubles as the liveness check.
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    fun stream(server: String, topics: Set<String>, readToken: String?): Flow<NtfyMessage> {
        require(topics.isNotEmpty()) { "No topics to subscribe to" }
        return readFrames(server, topics, readToken).retryWhen { cause, attempt ->
            if (cause is FatalStreamException) throw cause
            val backoff = clock.backoff(attempt)
            Timber.tag(loggerTag.value).w("Stream broke (${cause.message}), reconnecting in $backoff")
            clock.delay(backoff)
            true
        }.flowOn(Dispatchers.IO)
    }

    private fun readFrames(server: String, topics: Set<String>, readToken: String?): Flow<NtfyMessage> = flow {
        val url = "${server.trimEnd('/')}/${topics.joinToString(",")}/json"
        val request = Request.Builder().url(url).apply {
            if (readToken != null) header("Authorization", "Bearer $readToken")
        }.build()

        val response = streamClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            if (response.code in 400..499) {
                throw FatalStreamException("Stream refused by the server: HTTP ${response.code}")
            }
            throw IOException("Stream failed: HTTP ${response.code}")
        }

        val source = response.body.source()
        Timber.tag(loggerTag.value).d("Stream open for ${topics.size} topic(s) on $server")
        while (true) {
            val line = source.readUtf8Line() ?: throw IOException("Stream closed by the server")
            parseFrame(line)?.let { emit(it) }
        }
    }

    private fun parseFrame(line: String): NtfyMessage? {
        if (line.isBlank()) return null
        val frame = runCatchingExceptions { json.decodeFromString<NtfyFrame>(line) }
            .onFailure { Timber.tag(loggerTag.value).w("Skipping an unreadable frame") }
            .getOrNull()
            ?: return null
        return if (frame.event == "message" && frame.topic != null && frame.message != null) {
            NtfyMessage(topic = frame.topic, content = frame.message)
        } else {
            null
        }
    }

    @Serializable
    private data class NtfyFrame(
        val event: String,
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
        const val READ_TIMEOUT_S = 95L
        const val BACKOFF_CAP_S = 15L
    }
}
