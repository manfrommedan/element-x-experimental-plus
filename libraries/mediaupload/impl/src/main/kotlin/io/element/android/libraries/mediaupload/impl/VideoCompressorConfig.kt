/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaupload.impl

import android.util.Size
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import io.element.android.libraries.androidutils.media.VideoCompressorHelper
import io.element.android.libraries.mediaupload.api.compressorHelper
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import kotlin.math.min

@OptIn(UnstableApi::class)
internal object VideoCompressorConfigFactory {
    private const val DEFAULT_FRAME_RATE = 30

    fun create(
        metadata: VideoFileMetadata?,
        preset: VideoCompressionPreset,
    ): VideoCompressorConfig {
        val width = metadata?.width?.takeIf { it >= 0 } ?: Int.MAX_VALUE
        val height = metadata?.height?.takeIf { it >= 0 } ?: Int.MAX_VALUE
        val originalFrameRate = metadata?.frameRate?.takeIf { it >= 0 } ?: DEFAULT_FRAME_RATE

        val resizer = preset.compressorHelper()

        // If we are resizing, we also want to reduce the frame rate to the default value (30fps)
        val newFrameRate = min(originalFrameRate, DEFAULT_FRAME_RATE)

        // If we need to resize the video, we also want to recalculate the bitrate
        val optimalBitrate = resizer.calculateOptimalBitrate(Size(width, height), newFrameRate).toInt()

        // METADATA_KEY_BITRATE covers the whole container, video plus audio, so it can only
        // over-estimate the video track. That still makes it a safe ceiling: encoding above what
        // the source carries spends bytes without adding a single pixel of detail. Without it a
        // clip filmed at 720p/1.5Mbps comes out of the STANDARD preset at 2.8Mbps, so the
        // "compression" step nearly doubles the upload.
        val sourceBitrate = metadata?.bitrate?.takeIf { it > 0 }
        val newBitrate = sourceBitrate?.let { min(optimalBitrate.toLong(), it).toInt() } ?: optimalBitrate

        // Nothing to scale down and nothing to shave off the bitrate: a re-encode would only cost
        // time and quality. Ask for a remux instead, which still runs through the muxer that strips
        // the metadata.
        val canRemux = metadata != null &&
            sourceBitrate != null &&
            sourceBitrate <= optimalBitrate &&
            resizer.getOutputSize(Size(width, height)) == Size(width, height)

        return VideoCompressorConfig(
            videoCompressorHelper = resizer,
            newBitRate = newBitrate,
            newFrameRate = newFrameRate,
            canRemux = canRemux,
        )
    }
}

@OptIn(UnstableApi::class)
internal data class VideoCompressorConfig(
    val videoCompressorHelper: VideoCompressorHelper,
    val newBitRate: Int,
    val newFrameRate: Int,
    /** The source is already within the preset, so copy the streams instead of re-encoding them. */
    val canRemux: Boolean,
)
