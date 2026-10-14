/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.selfpush

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.extensions.runCatchingExceptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// runBlocking, not runTest, on purpose: the streams do real socket IO off the test scheduler,
// and virtual time would starve them.
class NtfyStreamClientTest {
    private val server = MockWebServer()
    private val wsServer = MiniWebSocketServer()

    @Before
    fun setUp() {
        server.start()
        wsServer.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        wsServer.close()
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

    private fun client(
        clock: NtfyStreamClient.BackoffClock = RecordingClock(),
        liveness: NtfyStreamClient.Liveness = NtfyStreamClient.Liveness(),
    ) = NtfyStreamClient(OkHttpClient(), clock, liveness)

    private fun serverUrl() = server.url("/").toString().trimEnd('/')

    private fun httpFrames(vararg lines: String) = MockResponse().setBody(lines.joinToString("\n", postfix = "\n"))

    private fun messageFrame(id: String, time: Long, topic: String, content: String) =
        """{"id":"$id","time":$time,"event":"message","topic":"$topic","message":"$content"}"""

    @Test
    fun `http message frames are emitted with id, topic, content and time`() = runBlocking {
        server.enqueue(
            httpFrames(
                """{"id":"0","time":1,"event":"open"}""",
                """{"id":"k","time":2,"event":"keepalive","topic":"upA"}""",
                messageFrame("m1", 100, "upA", "first"),
                messageFrame("m2", 101, "upB", "second"),
            )
        )
        // The stream would loop on EOF; the terminal 401 ends it deterministically.
        server.enqueue(MockResponse().setResponseCode(401))

        val received = mutableListOf<NtfyMessage>()
        var error: Throwable? = null
        client().stream(serverUrl(), setOf("upA", "upB"), readToken = null, SelfPushProtocol.JSON_STREAM) { null }
            .test {
                received += awaitItem()
                received += awaitItem()
                error = awaitError()
            }

        assertThat(received.map { it.id to it.content }).containsExactly("m1" to "first", "m2" to "second").inOrder()
        assertThat(received.map { it.topic to it.time }).containsExactly("upA" to 100L, "upB" to 101L).inOrder()
        assertThat(error).isInstanceOf(FatalStreamException::class.java)
    }

    @Test
    fun `websocket message frames are emitted with id, topic, content and time`() = runBlocking {
        wsServer.enqueue(
            MiniWebSocketServer.SendFrames(
                """{"id":"0","time":1,"event":"open"}""",
                """{"id":"k","time":2,"event":"keepalive","topic":"upA"}""",
                messageFrame("m1", 100, "upA", "first"),
                messageFrame("m2", 101, "upB", "second"),
            )
        )
        wsServer.enqueue(MiniWebSocketServer.Refuse(401))

        val received = mutableListOf<NtfyMessage>()
        var error: Throwable? = null
        client().stream(wsServer.url, setOf("upA", "upB"), readToken = null, SelfPushProtocol.WEBSOCKET) { null }
            .test {
                received += awaitItem()
                received += awaitItem()
                error = awaitError()
            }

        assertThat(received.map { it.id to it.content }).containsExactly("m1" to "first", "m2" to "second").inOrder()
        assertThat(received.map { it.topic to it.time }).containsExactly("upA" to 100L, "upB" to 101L).inOrder()
        assertThat(error).isInstanceOf(FatalStreamException::class.java)
    }

    @Test
    fun `broken http stream reconnects until a message arrives`() = runBlocking {
        val clock = RecordingClock()
        server.enqueue(httpFrames("""{"id":"0","time":1,"event":"open"}"""))
        server.enqueue(httpFrames("""{"id":"1","time":2,"event":"keepalive"}"""))
        server.enqueue(httpFrames(messageFrame("m1", 100, "upA", "after-reconnect")))
        server.enqueue(MockResponse().setResponseCode(401))

        client(clock).stream(serverUrl(), setOf("upA"), readToken = null, SelfPushProtocol.JSON_STREAM) { null }
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
    fun `a broken stream reports each reconnect through onReconnecting`() = runBlocking {
        val clock = RecordingClock()
        val reconnectCauses = mutableListOf<Throwable>()
        server.enqueue(httpFrames("""{"id":"0","time":1,"event":"open"}"""))
        server.enqueue(MockResponse().setResponseCode(401))

        client(clock).stream(
            serverUrl(),
            setOf("upA"),
            readToken = null,
            SelfPushProtocol.JSON_STREAM,
            onReconnecting = { cause, _ -> reconnectCauses += cause },
        ) { null }
            .test {
                awaitError()
            }

        // The first response's EOF breaks the stream, the reconnect lands on the terminal 401.
        assertThat(reconnectCauses).hasSize(1)
    }

    @Test
    fun `broken websocket stream reconnects until a message arrives`() = runBlocking {
        val clock = RecordingClock()
        wsServer.enqueue(MiniWebSocketServer.SendFrames("""{"id":"0","time":1,"event":"open"}"""))
        wsServer.enqueue(MiniWebSocketServer.SendFrames(messageFrame("m1", 100, "upA", "after-reconnect")))
        wsServer.enqueue(MiniWebSocketServer.Refuse(401))

        client(clock).stream(wsServer.url, setOf("upA"), readToken = null, SelfPushProtocol.WEBSOCKET) { null }
            .test {
                val message = awaitItem()
                assertThat(message.content).isEqualTo("after-reconnect")
                awaitError()
            }

        assertThat(clock.reconnects).isEqualTo(2)
    }

    @Test
    fun `silent websocket stays open, liveness is the ping heartbeat`() = runBlocking {
        val clock = RecordingClock()
        // The server says nothing at all: an idle ntfy WebSocket carries no application
        // frames, only ping control frames the listener never sees. The stream must not be
        // killed for that silence — dead peers are detected by the ping/pong heartbeat.
        wsServer.enqueue(MiniWebSocketServer.Silent)

        val job = launch {
            client(clock).stream(wsServer.url, setOf("upA"), readToken = null, SelfPushProtocol.WEBSOCKET) { null }
                .collect { }
        }
        // Let the stream connect and sit in silence, then cancel: only the cancellation
        // may end it.
        delay(500)
        job.cancel()

        withTimeout(2.seconds) { job.join() }
        assertThat(clock.reconnects).isEqualTo(0)
    }

    @Test
    fun `rate limited stream reconnects instead of dying`() = runBlocking {
        val clock = RecordingClock()
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(401))

        client(clock).stream(serverUrl(), setOf("upA"), readToken = null, SelfPushProtocol.JSON_STREAM) { null }
            .test {
                assertThat(awaitError()).isInstanceOf(FatalStreamException::class.java)
            }

        // 429 retried (then the terminal 401 ended the flow), so one reconnect happened.
        assertThat(clock.reconnects).isEqualTo(1)
    }

    @Test
    fun `http stream releases the connection when the collector is cancelled`() = runBlocking {
        // A keepalive-only stream that never ends on its own.
        server.enqueue(
            MockResponse()
                .setBody("""{"id":"0","time":1,"event":"open"}""" + "\n")
                .setSocketPolicy(SocketPolicy.KEEP_OPEN)
        )

        val job = launch {
            client().stream(serverUrl(), setOf("upA"), readToken = null, SelfPushProtocol.JSON_STREAM) { null }
                .collect { }
        }
        // Let the stream connect and settle into the blocking read, then cancel.
        // (No takeRequest here: it would block the runBlocking loop before collect even runs.)
        delay(500)
        job.cancel()

        // Without the call cancellation the collector would hang on the socket forever.
        withTimeout(2.seconds) { job.join() }
        assertThat(job.isCancelled).isTrue()
        assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull()
    }

    @Test
    fun `onOpen fires once the http stream is up`() = runBlocking {
        var opens = 0
        server.enqueue(httpFrames("""{"id":"0","time":1,"event":"open"}"""))
        server.enqueue(MockResponse().setResponseCode(401))

        client().stream(serverUrl(), setOf("upA"), readToken = null, SelfPushProtocol.JSON_STREAM, onOpen = { opens++ }) { null }
            .test { awaitError() }

        assertThat(opens).isEqualTo(1)
    }

    @Test
    fun `onOpen fires once the websocket stream is up`() = runBlocking {
        var opens = 0
        wsServer.enqueue(MiniWebSocketServer.SendFrames())
        wsServer.enqueue(MiniWebSocketServer.Refuse(401))

        client().stream(wsServer.url, setOf("upA"), readToken = null, SelfPushProtocol.WEBSOCKET, onOpen = { opens++ }) { null }
            .test { awaitError() }

        assertThat(opens).isEqualTo(1)
    }

    @Test
    fun `refused stream fails fatally without reconnecting`() = runBlocking {
        val clock = RecordingClock()
        wsServer.enqueue(MiniWebSocketServer.Refuse(403))

        client(clock).stream(wsServer.url, setOf("upA"), readToken = null, SelfPushProtocol.WEBSOCKET) { null }
            .test {
                assertThat(awaitError()).isInstanceOf(FatalStreamException::class.java)
            }

        assertThat(clock.reconnects).isEqualTo(0)
    }

    @Test
    fun `read token travels as a bearer header`() = runBlocking {
        wsServer.enqueue(MiniWebSocketServer.Refuse(401))

        client().stream(wsServer.url, setOf("upA"), readToken = "tk_secret", SelfPushProtocol.WEBSOCKET) { null }
            .test { awaitError() }

        assertThat(wsServer.requests.single().authorization).isEqualTo("Bearer tk_secret")
    }

    @Test
    fun `since parameter is forwarded on the http stream url`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        client().stream(serverUrl(), setOf("upA"), readToken = null, SelfPushProtocol.JSON_STREAM) { 1234L }
            .test { awaitError() }

        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/upA/json?since=1234")
    }

    @Test
    fun `since parameter is forwarded on the websocket url and refreshed on reconnect`() = runBlocking {
        wsServer.enqueue(MiniWebSocketServer.SendFrames())
        wsServer.enqueue(MiniWebSocketServer.Refuse(401))

        // Evaluated synchronously per connect attempt: 1000 on the first, 2000 on the retry.
        var sinceCalls = 0L
        client().stream(wsServer.url, setOf("upA"), readToken = null, SelfPushProtocol.WEBSOCKET) { ++sinceCalls * 1000 }
            .test { awaitError() }

        assertThat(wsServer.requests).hasSize(2)
        assertThat(wsServer.requests[0].path).isEqualTo("/upA/ws?since=1000")
        assertThat(wsServer.requests[1].path).isEqualTo("/upA/ws?since=2000")
    }

    @Test
    fun `no since parameter on the first connect`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        client().stream(serverUrl(), setOf("upA"), readToken = null, SelfPushProtocol.JSON_STREAM) { null }
            .test { awaitError() }

        assertThat(server.takeRequest().path).isEqualTo("/upA/json")
    }
}

/**
 * A minimal WebSocket server for the stream tests. MockWebServer 5 dropped socket upgrades,
 * so this does the handshake and the framing by hand: text frames are short enough to never
 * need the 64-bit length form, and client frames are only read far enough to spot a close.
 */
private class MiniWebSocketServer : Closeable {
    sealed interface Behavior

    /** Completes the handshake, sends each frame, then a close frame. */
    data class SendFrames(val frames: List<String>) : Behavior {
        constructor(vararg frames: String) : this(frames.toList())
    }

    /** Completes the handshake and then stays quiet, simulating a dead peer. */
    data object Silent : Behavior

    /** Answers the handshake with an HTTP error instead. */
    data class Refuse(val code: Int) : Behavior

    data class RecordedRequest(val path: String, val authorization: String?)

    val requests = CopyOnWriteArrayList<RecordedRequest>()

    val url: String
        get() = "http://127.0.0.1:${serverSocket.localPort}"

    private val serverSocket = ServerSocket()
    private val behaviors = ArrayBlockingQueue<Behavior>(16)
    private val openSockets = CopyOnWriteArrayList<Socket>()
    private var acceptThread: Thread? = null
    @Volatile private var closed = false

    fun enqueue(behavior: Behavior) {
        behaviors.add(behavior)
    }

    fun start() {
        serverSocket.bind(java.net.InetSocketAddress("127.0.0.1", 0))
        acceptThread = thread(name = "fake-ws-server", isDaemon = true) {
            while (!closed) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    break
                }
                openSockets += socket
                thread(name = "fake-ws-conn", isDaemon = true) {
                    runCatchingExceptions { handle(socket) }
                    runCatchingExceptions { socket.close() }
                    openSockets -= socket
                }
            }
        }
    }

    override fun close() {
        closed = true
        openSockets.forEach { runCatchingExceptions { it.close() } }
        runCatchingExceptions { serverSocket.close() }
        acceptThread?.join(2000)
    }

    private fun handle(socket: Socket) {
        val behavior = behaviors.take()
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        val head = readHttpHead(input)
        val lines = head.split("\r\n")
        val path = lines.first().split(" ").getOrNull(1) ?: "/"
        val authorization = lines.firstOrNull { it.startsWith("Authorization:") }?.substringAfter(":")?.trim()
        requests += RecordedRequest(path = path, authorization = authorization)

        if (behavior is Refuse) {
            output.write("HTTP/1.1 ${behavior.code} Refused\r\nContent-Length: 0\r\n\r\n".toByteArray())
            output.flush()
            return
        }

        val key = lines.first { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(":").trim()
        val accept = MessageDigest.getInstance("SHA-1")
            .digest((key + WEBSOCKET_GUID).toByteArray())
            .let { Base64.getEncoder().encodeToString(it) }
        output.write(
            (
                "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n"
                ).toByteArray()
        )
        output.flush()

        if (behavior is SendFrames) {
            behavior.frames.forEach { writeTextFrame(output, it) }
            writeCloseFrame(output)
        }
        // Read the client's frames until its close (or EOF): a WebSocket close is a handshake,
        // and the client waits for our echo before reporting the stream as closed.
        readClientFramesUntilClose(input, output)
    }

    private fun readHttpHead(input: InputStream): String {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val byte = input.read()
            check(byte != -1) { "Connection closed before the handshake completed" }
            head.append(byte.toChar())
        }
        return head.toString()
    }

    private fun writeTextFrame(output: OutputStream, text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        output.write(0x81)
        if (payload.size < 126) {
            output.write(payload.size)
        } else {
            output.write(126)
            output.write(payload.size shr 8)
            output.write(payload.size and 0xFF)
        }
        output.write(payload)
        output.flush()
    }

    private fun writeCloseFrame(output: OutputStream) {
        output.write(byteArrayOf(0x88.toByte(), 0x00))
        output.flush()
    }

    private fun readClientFramesUntilClose(input: InputStream, output: OutputStream) {
        while (true) {
            val b0 = input.read()
            val b1 = input.read()
            if (b0 == -1 || b1 == -1) return
            val opcode = b0 and 0x0F
            var length = b1 and 0x7F
            if (length == 126) {
                length = input.read() shl 8 or input.read()
            } else if (length == 127) {
                repeat(8) { input.read() }
                length = 0
            }
            if (b1 and 0x80 != 0) {
                repeat(4) { input.read() }
            }
            repeat(length) { input.read() }
            if (opcode == OPCODE_CLOSE) {
                writeCloseFrame(output)
                return
            }
        }
    }

    private companion object {
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val OPCODE_CLOSE = 0x8
    }
}
