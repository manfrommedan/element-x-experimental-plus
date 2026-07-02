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
import org.jsoup.nodes.Document
import java.net.URI

internal fun findFirstPreviewableUrl(
    formattedBody: CharSequence,
    htmlDocument: Document?,
    permalinkParser: PermalinkParser? = null,
): String? {
    val textUrls = formattedBody.extractUrlSpans()
        .ifEmpty { extractRawTextUrls(formattedBody.toString()) }
    val htmlUrls = htmlDocument
        ?.select("a[href]")
        ?.map { it.attr("href") }
        .orEmpty()
    return (textUrls + htmlUrls).firstOrNull { isPreviewableUrl(it, permalinkParser) }
}

internal fun isPreviewableUrl(url: String, permalinkParser: PermalinkParser? = null): Boolean {
    val uri = tryOrNull { URI(url) } ?: return false
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
    // Authoritative: a Matrix mention or permalink (@user, room link, event permalink) renders as a
    // link, and the Matrix parser recognises it as an identifier rather than a FallbackLink. Those
    // are not content to preview, so a "@nickname" mention must not fetch a website preview.
    if (permalinkParser != null && permalinkParser.parse(url) !is PermalinkData.FallbackLink) {
        return false
    }
    // Fallback when no parser is available (previews, tests): recognise matrix.to and matrix-style
    // permalink fragments by shape so mentions are still skipped.
    if (uri.host?.lowercase()?.removePrefix("www.") == "matrix.to") return false
    val fragment = uri.fragment.orEmpty()
    if (matrixPermalinkFragmentSigils.any { fragment.startsWith(it) }) return false
    return true
}

// matrix.to-style permalink fragments: /#/@user, /#/!room, /#/#alias, /#/$event.
private val matrixPermalinkFragmentSigils = listOf("/@", "/!", "/#", "/\$")

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
        .map { it.url }
}

private val rawUrlRegex = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

private fun extractRawTextUrls(text: String): List<String> {
    return rawUrlRegex.findAll(text)
        .map { matchResult -> matchResult.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}') }
        .toList()
}
