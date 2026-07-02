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
import org.junit.Test

class UrlPreviewParserMentionTest : RobolectricTest() {
    @Test
    fun `a url auto-linkified from a mention matrix id is not previewable`() {
        // A mention is a MentionSpan pill whose text carries the full matrix id; Linkify attaches a
        // URLSpan over the "example.org" tail, inside the mention. That must not become a preview.
        val text = "@alice:example.org hi"
        val spannable = SpannableString(text)
        spannable.setSpan(
            MentionSpan(MentionType.User(UserId("@alice:example.org"))),
            0,
            "@alice:example.org".length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        val domainStart = text.indexOf("example.org")
        spannable.setSpan(
            URLSpan("http://example.org"),
            domainStart,
            domainStart + "example.org".length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )

        assertThat(findFirstPreviewableUrl(spannable, htmlDocument = null)).isNull()
    }

    @Test
    fun `a real link outside any mention is still previewable`() {
        val text = "@alice:example.org see https://example.com/article"
        val spannable = SpannableString(text)
        spannable.setSpan(
            MentionSpan(MentionType.User(UserId("@alice:example.org"))),
            0,
            "@alice:example.org".length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        val linkStart = text.indexOf("https://example.com/article")
        spannable.setSpan(
            URLSpan("https://example.com/article"),
            linkStart,
            linkStart + "https://example.com/article".length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )

        assertThat(findFirstPreviewableUrl(spannable, htmlDocument = null))
            .isEqualTo("https://example.com/article")
    }
}
