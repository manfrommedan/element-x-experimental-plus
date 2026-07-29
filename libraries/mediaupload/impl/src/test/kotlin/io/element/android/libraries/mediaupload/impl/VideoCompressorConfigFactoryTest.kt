/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaupload.impl

import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test
@Suppress("NOTHING_TO_INLINE")
class VideoCompressorConfigFactoryTest : RobolectricTest() {
    @Test
    fun `if we don't have metadata the video will be resized`() {
        // Given
        val metadata = null
        val preset = VideoCompressionPreset.STANDARD

        // When
        val videoCompressorConfig = VideoCompressorConfigFactory.create(
            metadata = metadata,
            preset = preset,
        )

        // Then
        assertThat(videoCompressorConfig.videoCompressorHelper).isNotNull()
        assertThat(videoCompressorConfig.newFrameRate).isEqualTo(30)
        assertThat(videoCompressorConfig.newBitRate).isNotEqualTo(VideoEncoderSettings.NO_VALUE)
    }

    @Test
    fun `if the video should be compressed and is larger than 720p it will be resized`() {
        // Given
        val metadata = VideoFileMetadata(width = 1920, height = 1080, bitrate = 1_000_000, frameRate = 50, rotation = 0)
        val preset = VideoCompressionPreset.STANDARD

        // When
        val videoCompressorConfig = VideoCompressorConfigFactory.create(
            metadata = metadata,
            preset = preset,
        )

        // Then
        assertIsResized(videoCompressorConfig, metadata.width)
    }

    @Test
    fun `if the video should be compressed and is smaller or equal to 720p it will not be resized`() {
        // Given
        val metadata = VideoFileMetadata(width = 1280, height = 720, bitrate = 1_000_000, frameRate = 50, rotation = 0)
        val preset = VideoCompressionPreset.STANDARD

        // When
        val videoCompressorConfig = VideoCompressorConfigFactory.create(
            metadata = metadata,
            preset = preset,
        )

        // Then
        assertIsNotResized(videoCompressorConfig, 1280)
    }

    @Test
    fun `if the video should not be compressed and is larger than 1080p it will be resized`() {
        // Given
        val metadata = VideoFileMetadata(width = 2560, height = 1440, bitrate = 1_000_000, frameRate = 50, rotation = 0)
        val preset = VideoCompressionPreset.HIGH

        // When
        val videoCompressorConfig = VideoCompressorConfigFactory.create(
            metadata = metadata,
            preset = preset,
        )

        // Then
        assertIsResized(videoCompressorConfig, metadata.width)
    }

    @Test
    fun `if the video should not be compressed and is smaller or equal than 1080p it will not be resized`() {
        // Given
        val metadata = VideoFileMetadata(width = 1920, height = 1080, bitrate = 1_000_000, frameRate = 50, rotation = 0)
        val preset = VideoCompressionPreset.HIGH

        // When
        val videoCompressorConfig = VideoCompressorConfigFactory.create(
            metadata = metadata,
            preset = preset,
        )

        // Then
        assertIsNotResized(videoCompressorConfig, 1920)
    }

    @Test
    fun `the bitrate never goes above what the source already carries`() {
        // Given a 720p clip at 1.5Mbps, well under the 2.76Mbps the STANDARD preset would ask for
        val metadata = VideoFileMetadata(width = 1280, height = 720, bitrate = 1_500_000, frameRate = 30, rotation = 0)

        // When
        val videoCompressorConfig = VideoCompressorConfigFactory.create(
            metadata = metadata,
            preset = VideoCompressionPreset.STANDARD,
        )

        // Then re-encoding it at the preset bitrate would have grown the file, so it is capped
        assertThat(videoCompressorConfig.newBitRate).isEqualTo(1_500_000)
    }

    @Test
    fun `a source that already fits the preset is remuxed instead of re-encoded`() {
        // Given
        val metadata = VideoFileMetadata(width = 1280, height = 720, bitrate = 1_500_000, frameRate = 30, rotation = 0)

        // When
        val videoCompressorConfig = VideoCompressorConfigFactory.create(
            metadata = metadata,
            preset = VideoCompressionPreset.STANDARD,
        )

        // Then
        assertThat(videoCompressorConfig.canRemux).isTrue()
    }

    @Test
    fun `a source that is too big or too rich is still re-encoded`() {
        // Too many pixels for the preset
        assertThat(
            VideoCompressorConfigFactory.create(
                metadata = VideoFileMetadata(width = 1920, height = 1080, bitrate = 1_500_000, frameRate = 30, rotation = 0),
                preset = VideoCompressionPreset.STANDARD,
            ).canRemux
        ).isFalse()

        // Right size, but far more bitrate than the preset would spend
        assertThat(
            VideoCompressorConfigFactory.create(
                metadata = VideoFileMetadata(width = 1280, height = 720, bitrate = 12_000_000, frameRate = 30, rotation = 0),
                preset = VideoCompressionPreset.STANDARD,
            ).canRemux
        ).isFalse()
    }

    @Test
    fun `an unreadable bitrate falls back to re-encoding at the preset`() {
        // Given metadata without a usable bitrate, as MediaMetadataRetriever reports for some files
        val metadata = VideoFileMetadata(width = 1280, height = 720, bitrate = -1, frameRate = 30, rotation = 0)

        // When
        val videoCompressorConfig = VideoCompressorConfigFactory.create(
            metadata = metadata,
            preset = VideoCompressionPreset.STANDARD,
        )

        // Then
        assertThat(videoCompressorConfig.canRemux).isFalse()
        assertThat(videoCompressorConfig.newBitRate).isEqualTo(2_764_800)
    }

    private inline fun assertIsResized(videoCompressorConfig: VideoCompressorConfig, referenceSize: Int) {
        assertThat(videoCompressorConfig.videoCompressorHelper.maxSize).isNotEqualTo(referenceSize)
    }

    private inline fun assertIsNotResized(videoCompressorConfig: VideoCompressorConfig, referenceSize: Int) {
        assertThat(videoCompressorConfig.videoCompressorHelper.maxSize).isEqualTo(referenceSize)
    }
}
