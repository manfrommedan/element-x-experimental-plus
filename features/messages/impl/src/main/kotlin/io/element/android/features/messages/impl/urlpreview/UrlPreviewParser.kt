/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.urlpreview

import android.text.Spanned
import android.text.style.URLSpan
import io.element.android.libraries.core.data.tryOrNull
import io.element.android.libraries.matrix.api.permalink.PermalinkData
import io.element.android.libraries.matrix.api.permalink.PermalinkParser
import io.element.android.libraries.textcomposer.mentions.getMentionSpans
import org.jsoup.nodes.Document
import java.net.URI

internal fun findFirstPreviewableUrl(
    formattedBody: CharSequence,
    htmlDocument: Document?,
    permalinkParser: PermalinkParser? = null,
): String? {
    // Prefer the rendered links, falling back to raw scanning only when there are none. Short-circuit
    // on the first previewable url so we never traverse the HTML document (jsoup select) when a text
    // link already matches.
    val textUrls = formattedBody.extractUrlSpans()
        .ifEmpty { extractRawTextUrls(formattedBody.toString()) }
    textUrls.firstOrNull { isPreviewableUrl(it, permalinkParser) }?.let { return it }
    return htmlDocument
        ?.select("a[href]")
        ?.firstOrNull { isPreviewableUrl(it.attr("href"), permalinkParser) }
        ?.attr("href")
}

internal fun isPreviewableUrl(url: String, permalinkParser: PermalinkParser? = null): Boolean {
    val uri = tryOrNull { URI(url) } ?: return false
    if (uri.scheme?.lowercase() !in previewableSchemes) return false
    // Authoritative: a Matrix mention or permalink (@user, room link, event permalink) renders as a
    // link, and the Matrix parser recognises it as an identifier rather than a FallbackLink. Those
    // are not content to preview, so a "@nickname" mention must not fetch a website preview.
    if (permalinkParser != null && permalinkParser.parse(url) !is PermalinkData.FallbackLink) {
        return false
    }
    // Fallback when no parser is available (previews, tests) and a safety net for malformed permalink
    // URLs the parser may reject: some clients/bridges emit a mention href with extra slashes
    // ("https:////matrix.to/#/@user"), whose authority no longer parses as matrix.to. A matrix.to-style
    // permalink always carries a "#/<sigil>" identifier fragment; collapse any run of slashes first so
    // the marker matches no matter how the scheme, host or fragment slashes were mangled.
    if (uri.host?.lowercase()?.removePrefix("www.") == "matrix.to") return false
    val normalizedUrl = url.lowercase().replace(repeatedSlashRegex, "/")
    if (matrixPermalinkMarkers.any { normalizedUrl.contains(it) }) return false
    return true
}

private val previewableSchemes = setOf("http", "https")
private val repeatedSlashRegex = Regex("/{2,}")

// matrix.to permalink identifier fragments: #/@user, #/!room, #/#alias, #/$event.
private val matrixPermalinkMarkers = listOf("#/@", "#/!", "#/#", "#/\$")

internal fun hostNameFromUrl(url: String): String {
    return tryOrNull { URI(url).host.orEmpty().removePrefix("www.") }
        ?.takeIf { it.isNotBlank() }
        ?: url
}

private fun CharSequence.extractUrlSpans(): List<String> {
    val spanned = this as? Spanned ?: return emptyList()
    return spanned.getSpans(0, spanned.length, URLSpan::class.java)
        .orEmpty()
        .sortedBy { spanned.getSpanStart(it) }
        // A mention pill's text can hold the user's full matrix id, and Linkify turns the ":server"
        // tail into a URLSpan. That span belongs to the mention, not to a real link, so drop any
        // URLSpan that overlaps a mention.
        .filter { spanned.getMentionSpans(spanned.getSpanStart(it), spanned.getSpanEnd(it)).isEmpty() }
        .map { it.url }
}

private val rawUrlRegex = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

private fun extractRawTextUrls(text: String): List<String> {
    return rawUrlRegex.findAll(text)
        .map { matchResult -> matchResult.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}') }
        .toList()
}
