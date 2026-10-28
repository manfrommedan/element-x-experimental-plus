/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.ui.media

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.matrix.api.media.MediaFile
import io.element.android.libraries.matrix.api.media.MediaSource
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The wifi-only auto-download gate relies on this fetcher honoring
 * [MediaRequestData.allowNetwork]. When false, the fetcher must NOT call
 * [MatrixMediaLoader] (which would fetch over the wire). Coil's memory and
 * disk caches sit above the fetcher, so previously-loaded thumbnails are
 * still served before fetch() is ever invoked.
 */
class CoilMediaFetcherTest {
    @Test
    fun `allowNetwork=false short-circuits the fetch and does not touch MediaLoader`() = runTest {
        val loader = CallTrackingMediaLoader()
        val fetcher = CoilMediaFetcher(
            mediaLoader = loader,
            mediaData = MediaRequestData(
                source = MediaSource("mxc://example.com/abc"),
                kind = MediaRequestData.Kind.Thumbnail(64),
                allowNetwork = false,
            ),
        )
        val result = fetcher.fetch()
        assertThat(result).isNull()
        assertThat(loader.thumbnailCalls).isEqualTo(0)
        assertThat(loader.contentCalls).isEqualTo(0)
        assertThat(loader.fileCalls).isEqualTo(0)
    }

    @Test
    fun `allowNetwork=true (default) delegates to MediaLoader for thumbnails`() = runTest {
        val loader = CallTrackingMediaLoader()
        val fetcher = CoilMediaFetcher(
            mediaLoader = loader,
            mediaData = MediaRequestData(
                source = MediaSource("mxc://example.com/abc"),
                kind = MediaRequestData.Kind.Thumbnail(64),
            ),
        )
        fetcher.fetch()
        assertThat(loader.thumbnailCalls).isEqualTo(1)
    }

    @Test
    fun `allowNetwork=false short-circuits for Content kind too`() = runTest {
        val loader = CallTrackingMediaLoader()
        val fetcher = CoilMediaFetcher(
            mediaLoader = loader,
            mediaData = MediaRequestData(
                source = MediaSource("mxc://example.com/abc"),
                kind = MediaRequestData.Kind.Content,
                allowNetwork = false,
            ),
        )
        assertThat(fetcher.fetch()).isNull()
        assertThat(loader.contentCalls).isEqualTo(0)
    }

    @Test
    fun `null source still returns null regardless of allowNetwork`() = runTest {
        val loader = CallTrackingMediaLoader()
        val fetcher = CoilMediaFetcher(
            mediaLoader = loader,
            mediaData = MediaRequestData(
                source = null,
                kind = MediaRequestData.Kind.Thumbnail(64),
                allowNetwork = true,
            ),
        )
        assertThat(fetcher.fetch()).isNull()
        assertThat(loader.thumbnailCalls).isEqualTo(0)
    }

    private class CallTrackingMediaLoader : MatrixMediaLoader {
        var thumbnailCalls = 0
        var contentCalls = 0
        var fileCalls = 0

        override suspend fun loadMediaContent(source: MediaSource): Result<ByteArray> {
            contentCalls += 1
            return Result.success(ByteArray(0))
        }

        override suspend fun loadMediaThumbnail(source: MediaSource, width: Long, height: Long): Result<ByteArray> {
            thumbnailCalls += 1
            return Result.success(ByteArray(0))
        }

        override suspend fun downloadMediaFile(
            source: MediaSource,
            mimeType: String?,
            filename: String?,
            useCache: Boolean,
        ): Result<MediaFile> {
            fileCalls += 1
            return Result.failure(IllegalStateException("not used"))
        }
    }
}
