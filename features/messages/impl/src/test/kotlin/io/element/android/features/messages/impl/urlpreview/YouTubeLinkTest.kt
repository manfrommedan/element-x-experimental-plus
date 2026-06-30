/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.urlpreview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class YouTubeLinkTest {
    @Test
    fun `recognises watch, short, shorts, embed and live links`() {
        assertThat(youTubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ")
        assertThat(youTubeVideoId("https://youtu.be/dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ")
        assertThat(youTubeVideoId("https://youtube.com/shorts/dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ")
        assertThat(youTubeVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ")
        assertThat(youTubeVideoId("https://www.youtube.com/live/dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ")
        assertThat(youTubeVideoId("https://music.youtube.com/watch?v=dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ")
    }

    @Test
    fun `ignores surrounding query parameters`() {
        assertThat(youTubeVideoId("https://www.youtube.com/watch?list=PL123&v=dQw4w9WgXcQ&t=30s"))
            .isEqualTo("dQw4w9WgXcQ")
        assertThat(youTubeVideoId("https://youtu.be/dQw4w9WgXcQ?t=30")).isEqualTo("dQw4w9WgXcQ")
    }

    @Test
    fun `rejects non-youtube and malformed links`() {
        assertThat(youTubeVideoId("https://example.org/watch?v=dQw4w9WgXcQ")).isNull()
        assertThat(youTubeVideoId("https://notyoutube.com/watch?v=dQw4w9WgXcQ")).isNull()
        assertThat(youTubeVideoId("https://www.youtube.com/feed/subscriptions")).isNull()
        assertThat(youTubeVideoId("https://www.youtube.com/watch?v=tooShort")).isNull()
        assertThat(youTubeVideoId("not a url")).isNull()
    }
}
