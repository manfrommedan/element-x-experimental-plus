/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.search

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.androidutils.json.DefaultJsonProvider
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.sessionstorage.test.InMemorySessionStore
import io.element.android.libraries.sessionstorage.test.aSessionData
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class DefaultMessageSearchEventResolverTest {
    private val roomId = RoomId("!room:server.org")
    private val editEventId = EventId("\$edit:server.org")
    private val originalEventId = EventId("\$original:server.org")

    @Test
    fun `an edit resolves to the original event id using an authorization header`() = runTest {
        var request: Request? = null
        val resolver = createResolver(
            response = """
                {
                  "content": {
                    "m.relates_to": {
                      "rel_type": "m.replace",
                      "event_id": "$originalEventId"
                    }
                  }
                }
            """.trimIndent(),
            onRequest = { request = it },
        )

        val result = resolver.resolve(roomId, editEventId)

        assertThat(result.getOrThrow()).isEqualTo(originalEventId)
        assertThat(request?.url.toString()).isEqualTo(
            "https://matrix.example.org/_matrix/client/v3/rooms/%21room%3Aserver.org/event/%24edit%3Aserver.org"
        )
        assertThat(request?.header("Authorization")).isEqualTo("Bearer token")
        assertThat(request?.url?.query).isNull()
    }

    @Test
    fun `an ordinary message keeps its event id`() = runTest {
        val resolver = createResolver(response = """{"content": {"body": "message"}}""")

        val result = resolver.resolve(roomId, editEventId)

        assertThat(result.getOrThrow()).isEqualTo(editEventId)
    }

    @Test
    fun `a replacement without an original event id fails`() = runTest {
        val resolver = createResolver(
            response = """{"content": {"m.relates_to": {"rel_type": "m.replace"}}}"""
        )

        val result = resolver.resolve(roomId, editEventId)

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `an http failure does not expose the access token`() = runTest {
        val resolver = createResolver(response = "error", responseCode = 500)

        val result = resolver.resolve(roomId, editEventId)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).doesNotContain("token")
    }

    @Test
    fun `cancelling resolution cancels the HTTP call`() = runTest {
        val requestStarted = java.util.concurrent.CountDownLatch(1)
        lateinit var call: Call
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestStarted.countDown()
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
                while (!chain.call().isCanceled() && System.nanoTime() < deadline) {
                    Thread.sleep(10)
                }
                throw java.io.IOException("Request aborted")
            }
            .build()
        val resolver = DefaultMessageSearchEventResolver(
            matrixClient = FakeMatrixClient(sessionId = SessionId(aSessionData().userId)),
            sessionStore = InMemorySessionStore(
                listOf(aSessionData().copy(homeserverUrl = "https://matrix.example.org", accessToken = "token"))
            ),
            callFactory = Call.Factory { request -> client.newCall(request).also { call = it } },
            json = DefaultJsonProvider(),
        )

        val job = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { resolver.resolve(roomId, editEventId) }
        assertThat(requestStarted.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
        job.cancel()
        job.join()

        assertThat(call.isCanceled()).isTrue()
    }

    @Test
    fun `a missing session fails before making a request`() = runTest {
        var requestCount = 0
        val resolver = DefaultMessageSearchEventResolver(
            matrixClient = FakeMatrixClient(),
            sessionStore = InMemorySessionStore(),
            callFactory = okHttpClient(response = "error", onRequest = { requestCount++ }),
            json = DefaultJsonProvider(),
        )

        val result = resolver.resolve(roomId, editEventId)

        assertThat(result.isFailure).isTrue()
        assertThat(requestCount).isEqualTo(0)
    }

    private fun createResolver(
        response: String,
        responseCode: Int = 200,
        onRequest: (Request) -> Unit = {},
    ): DefaultMessageSearchEventResolver {
        return DefaultMessageSearchEventResolver(
            matrixClient = FakeMatrixClient(sessionId = SessionId(aSessionData().userId)),
            sessionStore = InMemorySessionStore(
                listOf(aSessionData().copy(homeserverUrl = "https://matrix.example.org", accessToken = "token"))
            ),
            callFactory = okHttpClient(response, responseCode, onRequest),
            json = DefaultJsonProvider(),
        )
    }

    private fun okHttpClient(
        response: String,
        responseCode: Int = 200,
        onRequest: (Request) -> Unit = {},
    ) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            onRequest(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(responseCode)
                .message("test")
                .body(response.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()
}
