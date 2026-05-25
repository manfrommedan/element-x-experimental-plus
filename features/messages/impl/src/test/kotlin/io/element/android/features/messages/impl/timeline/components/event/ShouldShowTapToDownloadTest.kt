/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.event

import coil3.compose.AsyncImagePainter
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Test

class ShouldShowTapToDownloadTest {
    @Test
    fun `network allowed - error state - do NOT show overlay (let Coil retry naturally)`() {
        assertThat(shouldShowTapToDownload(networkAllowed = true, painterState = anErrorState())).isFalse()
    }

    @Test
    fun `network allowed - success state - do NOT show overlay`() {
        assertThat(shouldShowTapToDownload(networkAllowed = true, painterState = AsyncImagePainter.State.Empty)).isFalse()
    }

    @Test
    fun `wifi-only - error state - show overlay (cache miss, network blocked)`() {
        // The actual bug regression: in wifi-only mode the painter goes to Error
        // when Coil's cache lookup misses AND network policy is DISABLED. That's
        // the only state that should surface the tap-to-download prompt.
        assertThat(shouldShowTapToDownload(networkAllowed = false, painterState = anErrorState())).isTrue()
    }

    @Test
    fun `wifi-only - success state - do NOT show overlay (image is cached, show it)`() {
        // Pre-fix regression: this case used to show the overlay because the gate
        // was `if (shouldLoad) AsyncImage else Overlay` without consulting the
        // cache. Scrolling back to a previously-loaded thumbnail flashed the
        // download prompt instead of the picture.
        assertThat(shouldShowTapToDownload(networkAllowed = false, painterState = AsyncImagePainter.State.Empty)).isFalse()
    }

    @Test
    fun `wifi-only - empty state - do NOT show overlay (request not yet issued)`() {
        // Empty = request hasn't started. Don't pre-flash the overlay; wait until
        // Coil resolves to Success (cached) or Error (cache miss).
        assertThat(shouldShowTapToDownload(networkAllowed = false, painterState = AsyncImagePainter.State.Empty)).isFalse()
    }

    private fun anErrorState(): AsyncImagePainter.State.Error = AsyncImagePainter.State.Error(
        painter = null,
        // ImageRequest needs an Android Context (unavailable in plain JVM tests);
        // the predicate never reads it, so a relaxed mock is fine.
        result = ErrorResult(
            image = null,
            request = mockk<ImageRequest>(relaxed = true),
            throwable = RuntimeException("not in cache"),
        ),
    )
}
