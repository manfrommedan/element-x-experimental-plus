/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.selfpush

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration

class NtfyStreamClientTest {
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Reconnects instantly in tests, and counts how often it had to. */
    private class RecordingClock : NtfyStreamClient.BackoffClock() {
        var reconnects = 0
            private set

        override fun backoff(attempt: Long): Duration {
            reconnects++
            return Duration.ZERO
        }

        override suspend fun delay(duration: Duration) = Unit
    }

    private fun client(clock: NtfyStreamClient.BackoffClock = RecordingClock()) = NtfyStreamClient(OkHttpClient(), clock)

    private fun frames(vararg lines: String) = MockResponse().setBody(lines.joinToString("\n", postfix = "\n"))

    private fun messageFrame(topic: String, content: String) =
        """{"id":"1","time":1,"event":"message","topic":"$topic","message":${'"'}${content}${'"'}}"""

    @Test
    fun `message frames are emitted with their topic and content`() = runTest {
        server.enqueue(
            frames(
                """{"id":"0","time":1,"event":"open"}""",
                """{"id":"k","time":2,"event":"keepalive","topic":"upA"}""",
                messageFrame("upA", "first"),
                messageFrame("upB", "second"),
            )
        )
        // The stream would loop on EOF; the terminal 401 ends it deterministically.
        server.enqueue(MockResponse().setResponseCode(401))

        val received = mutableListOf<NtfyMessage>()
        var error: Throwable? = null
        client().stream(server.url("/").toString().trimEnd('/'), setOf("upA", "upB"), readToken = null)
            .test {
                received += awaitItem()
                received += awaitItem()
                error = awaitError()
            }

        assertThat(received.map { it.topic to it.content }).containsExactly("upA" to "first", "upB" to "second").inOrder()
        assertThat(error).isInstanceOf(FatalStreamException::class.java)
    }

    @Test
    fun `broken stream reconnects until a message arrives`() = runTest {
        val clock = RecordingClock()
        server.enqueue(frames("""{"id":"0","time":1,"event":"open"}"""))
        server.enqueue(frames("""{"id":"1","time":2,"event":"keepalive"}"""))
        server.enqueue(frames(messageFrame("upA", "after-reconnect")))
        server.enqueue(MockResponse().setResponseCode(401))

        client(clock).stream(server.url("/").toString().trimEnd('/'), setOf("upA"), readToken = null)
            .test {
                val message = awaitItem()
                assertThat(message.content).isEqualTo("after-reconnect")
                awaitError()
            }

        // Two dead streams before the message, then the message response's own EOF
        // triggers one more reconnect, which lands on the terminal 401.
        assertThat(clock.reconnects).isEqualTo(3)
    }

    @Test
    fun `refused stream fails fatally without reconnecting`() = runTest {
        val clock = RecordingClock()
        server.enqueue(MockResponse().setResponseCode(403))

        client(clock).stream(server.url("/").toString().trimEnd('/'), setOf("upA"), readToken = null)
            .test {
                assertThat(awaitError()).isInstanceOf(FatalStreamException::class.java)
            }

        assertThat(clock.reconnects).isEqualTo(0)
    }

    @Test
    fun `read token travels as a bearer header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        client().stream(server.url("/").toString().trimEnd('/'), setOf("upA"), readToken = "tk_secret")
            .test { awaitError() }

        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer tk_secret")
    }
}
