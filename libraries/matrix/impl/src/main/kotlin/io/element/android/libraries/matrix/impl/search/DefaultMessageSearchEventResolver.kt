/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.search

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.androidutils.json.JsonProvider
import io.element.android.libraries.core.extensions.mapCatchingExceptions
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.search.MessageSearchEventResolver
import io.element.android.libraries.sessionstorage.api.SessionStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.net.URI
import java.net.URLEncoder

@ContributesBinding(SessionScope::class)
@SingleIn(SessionScope::class)
class DefaultMessageSearchEventResolver(
    private val matrixClient: MatrixClient,
    private val sessionStore: SessionStore,
    private val json: JsonProvider,
) : MessageSearchEventResolver {
    override suspend fun resolve(roomId: RoomId, eventId: EventId): Result<EventId> {
        val sessionData = sessionStore.getSession(matrixClient.sessionId.value)
            ?: return Result.failure(IllegalStateException("Missing session"))
        val accessToken = sessionData.accessToken.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("Missing access token"))
        val url = buildEventUrl(sessionData.homeserverUrl, roomId, eventId, accessToken)

        return matrixClient.getUrl(url).mapCatchingExceptions { body ->
            val content = json().parseToJsonElement(body.decodeToString()).jsonObject["content"]?.jsonObject
            val relation = content?.get("m.relates_to")?.jsonObject
            if (relation?.stringValue("rel_type") == "m.replace") {
                return@mapCatchingExceptions relation.stringValue("event_id")
                    ?.takeIf { it.startsWith("$") }
                    ?.let(::EventId)
                    ?: throw IllegalStateException("Missing original event id")
            }
            if (content?.containsKey("ciphertext") == true) {
                Timber.d("Cannot resolve encrypted event relations for $eventId")
            }
            eventId
        }
    }
}

private fun buildEventUrl(homeserverUrl: String, roomId: RoomId, eventId: EventId, accessToken: String): String {
    val base = if (homeserverUrl.endsWith("/")) homeserverUrl else "$homeserverUrl/"
    val encodedRoomId = URLEncoder.encode(roomId.value, "UTF-8")
    val encodedEventId = URLEncoder.encode(eventId.value, "UTF-8")
    val encodedAccessToken = URLEncoder.encode(accessToken, "UTF-8")
    return URI(base).resolve("_matrix/client/v3/rooms/$encodedRoomId/event/$encodedEventId?access_token=$encodedAccessToken").toString()
}

private fun JsonObject.stringValue(key: String): String? {
    return get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
}
