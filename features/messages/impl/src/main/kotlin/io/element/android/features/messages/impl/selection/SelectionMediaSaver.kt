/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.selection

import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.core.extensions.mapCatchingExceptions
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.mediaviewer.api.MediaInfo
import io.element.android.libraries.mediaviewer.api.local.LocalMediaFactory
import io.element.android.libraries.mediaviewer.api.local.MediaFileSaver

/**
 * Fetches one file and writes it to Downloads.
 *
 * A step of its own rather than three calls inside the presenter: downloading, decrypting and
 * writing is the part that talks to Android, and keeping it here lets the presenter be tested
 * for what it decides rather than for what it plumbs.
 */
interface SelectionMediaSaver {
    suspend fun save(media: SavableMedia): Result<Unit>
}

@ContributesBinding(SessionScope::class)
class DefaultSelectionMediaSaver(
    private val mediaLoader: MatrixMediaLoader,
    private val localMediaFactory: LocalMediaFactory,
    private val mediaFileSaver: MediaFileSaver,
) : SelectionMediaSaver {
    override suspend fun save(media: SavableMedia): Result<Unit> {
        return mediaLoader.downloadMediaFile(
            source = media.source,
            mimeType = media.mimeType,
            filename = media.filename,
        ).mapCatchingExceptions { mediaFile ->
            // Closed once written: these are temp files, and thirty videos worth of them is not
            // something to leave lying around.
            mediaFile.use {
                val localMedia = localMediaFactory.createFromMediaFile(
                    mediaFile = it,
                    mediaInfo = media.toMediaInfo(),
                )
                mediaFileSaver.saveInDownloads(localMedia).getOrThrow()
            }
        }
    }
}

/**
 * What the media viewer's own saving path expects. Only the name and the type reach the file that
 * lands in Downloads; the rest of [MediaInfo] describes a viewer we are not opening.
 */
private fun SavableMedia.toMediaInfo(): MediaInfo = MediaInfo(
    filename = filename,
    caption = null,
    mimeType = mimeType,
    fileSize = null,
    formattedFileSize = "",
    fileExtension = filename.substringAfterLast('.', ""),
    senderId = null,
    senderName = null,
    senderAvatar = null,
    dateSent = null,
    dateSentFull = null,
    waveform = null,
    duration = null,
)
