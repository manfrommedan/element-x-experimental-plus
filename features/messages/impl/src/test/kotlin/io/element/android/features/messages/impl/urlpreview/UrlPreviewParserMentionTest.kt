/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.urlpreview

import android.text.SpannableString
import android.text.Spanned
import android.text.style.URLSpan
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.textcomposer.mentions.MentionSpan
import io.element.android.libraries.textcomposer.mentions.MentionType
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.wysiwyg.view.spans.CustomMentionSpan
import org.junit.Test

class UrlPreviewParserMentionTest : RobolectricTest() {
    // A mention pill's text carries the user's full matrix id (e.g. "@alice:example.org"); Linkify then
    // attaches a URLSpan over the "example.org" tail, inside the pill. That is not a real link.
    private fun spannableWithLinkifiedMention(mentionSpan: Any): SpannableString {
        val text = "@alice:example.org hi"
        return SpannableString(text).apply {
            setSpan(mentionSpan, 0, "@alice:example.org".length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val domainStart = text.indexOf("example.org")
            setSpan(URLSpan("http://example.org"), domainStart, domainStart + "example.org".length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    @Test
    fun `url linkified inside a CustomMentionSpan pill is not previewable`() {
        // CustomMentionSpan(MentionSpan) is the span the html converter attaches in the timeline.
        val mention = CustomMentionSpan(MentionSpan(MentionType.User(UserId("@alice:example.org"))))
        assertThat(findFirstPreviewableUrl(spannableWithLinkifiedMention(mention), htmlDocument = null)).isNull()
    }

    @Test
    fun `url linkified inside a MentionSpan pill is not previewable`() {
        val mention = MentionSpan(MentionType.User(UserId("@alice:example.org")))
        assertThat(findFirstPreviewableUrl(spannableWithLinkifiedMention(mention), htmlDocument = null)).isNull()
    }

    @Test
    fun `a real link outside any mention is still previewable`() {
        val text = "@alice:example.org see https://example.com/article"
        val spannable = SpannableString(text).apply {
            setSpan(
                CustomMentionSpan(MentionSpan(MentionType.User(UserId("@alice:example.org")))),
                0,
                "@alice:example.org".length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            val linkStart = text.indexOf("https://example.com/article")
            setSpan(URLSpan("https://example.com/article"), linkStart, linkStart + "https://example.com/article".length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        assertThat(findFirstPreviewableUrl(spannable, htmlDocument = null)).isEqualTo("https://example.com/article")
    }
}
