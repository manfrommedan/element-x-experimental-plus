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
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultMessageSearchEventResolverTest {
    private val roomId = RoomId("!room:server.org")
    private val editEventId = EventId("\$edit:server.org")
    private val originalEventId = EventId("\$original:server.org")

    @Test
    fun `an edit resolves to the original event id`() = runTest {
        var requestedUrl: String? = null
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
            onRequest = { requestedUrl = it },
        )

        val result = resolver.resolve(roomId, editEventId)

        assertThat(result.getOrThrow()).isEqualTo(originalEventId)
        assertThat(requestedUrl).isEqualTo(
            "https://matrix.example.org/_matrix/client/v3/rooms/%21room%3Aserver.org/event/%24edit%3Aserver.org?access_token=token"
        )
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
    fun `a missing session fails before making a request`() = runTest {
        var requestCount = 0
        val resolver = DefaultMessageSearchEventResolver(
            matrixClient = FakeMatrixClient(
                getUrlLambda = {
                    requestCount++
                    Result.success(ByteArray(0))
                }
            ),
            sessionStore = InMemorySessionStore(),
            json = DefaultJsonProvider(),
        )

        val result = resolver.resolve(roomId, editEventId)

        assertThat(result.isFailure).isTrue()
        assertThat(requestCount).isEqualTo(0)
    }

    private fun createResolver(
        response: String,
        onRequest: (String) -> Unit = {},
    ): DefaultMessageSearchEventResolver {
        return DefaultMessageSearchEventResolver(
            matrixClient = FakeMatrixClient(
                sessionId = SessionId(aSessionData().userId),
                getUrlLambda = {
                    onRequest(it)
                    Result.success(response.encodeToByteArray())
                }
            ),
            sessionStore = InMemorySessionStore(
                listOf(aSessionData().copy(homeserverUrl = "https://matrix.example.org", accessToken = "token"))
            ),
            json = DefaultJsonProvider(),
        )
    }
}
