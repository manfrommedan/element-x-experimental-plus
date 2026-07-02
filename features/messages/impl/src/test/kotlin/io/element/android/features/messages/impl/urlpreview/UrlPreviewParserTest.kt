/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.urlpreview

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.permalink.PermalinkData
import io.element.android.libraries.matrix.test.permalink.FakePermalinkParser
import org.jsoup.Jsoup
import org.junit.Test

class UrlPreviewParserTest {
    @Test
    fun `find first previewable url returns first previewable raw text url`() {
        val result = findFirstPreviewableUrl(
            formattedBody = "Mail me at jane@example.org or visit https://example.org/first then https://example.org/second",
            htmlDocument = null,
        )

        assertThat(result).isEqualTo("https://example.org/first")
    }

    @Test
    fun `find first previewable url falls back to html links`() {
        val result = findFirstPreviewableUrl(
            formattedBody = "No spans here",
            htmlDocument = Jsoup.parseBodyFragment("""<a href="https://example.org/path">example</a>"""),
        )

        assertThat(result).isEqualTo("https://example.org/path")
    }

    @Test
    fun `find first previewable url returns null when no urls found`() {
        val result = findFirstPreviewableUrl(
            formattedBody = "No URLs here at all",
            htmlDocument = null,
        )

        assertThat(result).isNull()
    }

    @Test
    fun `isPreviewableUrl returns true for https`() {
        assertThat(isPreviewableUrl("https://example.org")).isTrue()
    }

    @Test
    fun `isPreviewableUrl returns true for http`() {
        assertThat(isPreviewableUrl("http://example.org")).isTrue()
    }

    @Test
    fun `isPreviewableUrl returns false for ftp`() {
        assertThat(isPreviewableUrl("ftp://example.org")).isFalse()
    }

    @Test
    fun `isPreviewableUrl returns false for mailto`() {
        assertThat(isPreviewableUrl("mailto:user@example.org")).isFalse()
    }

    @Test
    fun `isPreviewableUrl returns false for malformed url`() {
        assertThat(isPreviewableUrl("not a url")).isFalse()
    }

    @Test
    fun `isPreviewableUrl returns false for matrix identifier links`() {
        // Mentions and permalinks must not be previewed.
        assertThat(isPreviewableUrl("https://matrix.to/#/@alice:example.org")).isFalse()
        assertThat(isPreviewableUrl("https://matrix.to/#/!room:example.org")).isFalse()
        assertThat(isPreviewableUrl("https://matrix.to/#/#alias:example.org")).isFalse()
        // Custom permalink base (not matrix.to) still carries a matrix identifier in the fragment.
        assertThat(isPreviewableUrl("https://element.example.org/#/@bob:example.org")).isFalse()
    }

    @Test
    fun `isPreviewableUrl uses the permalink parser to skip matrix identifiers`() {
        // The parser authoritatively recognises the link as a user identifier, even on a custom
        // permalink domain that the shape-based fallback would not catch.
        val parser = FakePermalinkParser { PermalinkData.UserLink(UserId("@alice:example.org")) }
        assertThat(isPreviewableUrl("https://custom.example.org/u/alice", parser)).isFalse()
    }

    @Test
    fun `find first previewable url skips a mention and returns the real link`() {
        val result = findFirstPreviewableUrl(
            formattedBody = "hey",
            htmlDocument = Jsoup.parseBodyFragment(
                """<a href="https://matrix.to/#/@alice:example.org">@alice</a> see <a href="https://example.org/x">this</a>""",
            ),
        )

        assertThat(result).isEqualTo("https://example.org/x")
    }

    @Test
    fun `hostNameFromUrl extracts hostname`() {
        assertThat(hostNameFromUrl("https://example.org/path")).isEqualTo("example.org")
    }

    @Test
    fun `hostNameFromUrl removes www prefix`() {
        assertThat(hostNameFromUrl("https://www.example.org/path")).isEqualTo("example.org")
    }

    @Test
    fun `hostNameFromUrl falls back to original url for invalid input`() {
        val input = "not a valid url"
        assertThat(hostNameFromUrl(input)).isEqualTo(input)
    }
}
