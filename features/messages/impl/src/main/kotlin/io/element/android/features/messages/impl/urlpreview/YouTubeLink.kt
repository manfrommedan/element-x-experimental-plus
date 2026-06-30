/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.urlpreview

import io.element.android.libraries.core.data.tryOrNull
import java.net.URI

// A video id is always 11 characters from this alphabet; anything else is not a watchable link.
private val videoIdRegex = Regex("""[A-Za-z0-9_-]{11}""")

/**
 * Extract the YouTube video id from a link, or null if [url] is not a recognisable YouTube video.
 * Handles watch URLs, the youtu.be short form, shorts, embeds and live links, ignoring extra query
 * parameters and trailing path segments. Only the official YouTube hosts are accepted so look-alike
 * domains are not matched.
 */
internal fun youTubeVideoId(url: String): String? {
    val uri = tryOrNull { URI(url) } ?: return null
    val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
    val isYouTube = host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
    if (!isYouTube) return null

    val candidate = if (host == "youtu.be") {
        uri.path.trim('/').substringBefore('/')
    } else {
        val queryId = uri.query
            ?.split('&')
            ?.firstOrNull { it.startsWith("v=") }
            ?.removePrefix("v=")
        queryId ?: uri.path
            .trim('/')
            .removePrefix("shorts/")
            .removePrefix("embed/")
            .removePrefix("live/")
            .substringBefore('/')
    }

    return candidate.takeIf { it.matches(videoIdRegex) }
}
