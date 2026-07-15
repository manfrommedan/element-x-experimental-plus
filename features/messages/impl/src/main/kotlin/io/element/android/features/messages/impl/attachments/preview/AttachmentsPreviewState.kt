/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.attachments.preview

import androidx.compose.runtime.Immutable
import io.element.android.features.messages.impl.attachments.Attachment
import io.element.android.features.messages.impl.attachments.preview.imageeditor.AttachmentImageEditorState
import io.element.android.features.messages.impl.attachments.video.MediaOptimizationSelectorState
import io.element.android.libraries.mediaupload.api.MediaUploadInfo
import io.element.android.libraries.textcomposer.model.TextEditorState
import kotlinx.collections.immutable.ImmutableList

data class AttachmentsPreviewState(
    val attachments: kotlinx.collections.immutable.ImmutableList<Attachment>,
    val currentIndex: Int,
    val imageEditorState: AttachmentImageEditorState?,
    val canEditImage: Boolean,
    val isApplyingImageEdits: Boolean,
    val displayImageEditError: Boolean,
    val sendActionState: SendActionState,
    val textEditorState: TextEditorState,
    val mediaOptimizationSelectorState: MediaOptimizationSelectorState,
    val displayFileTooLargeError: Boolean,
    val eventSink: (AttachmentsPreviewEvent) -> Unit,
) {
    val totalCount: Int = attachments.size
    val attachment: Attachment = attachments[currentIndex]
}

@Immutable
sealed interface SendActionState {
    data object Idle : SendActionState

    @Immutable
    sealed interface Sending : SendActionState {
        data class Processing(val displayProgress: Boolean) : Sending
        data class ReadyToUpload(val mediaInfos: List<MediaUploadInfo>) : Sending
        data class Uploading(
            val mediaInfos: List<MediaUploadInfo>,
            // Batch progress for the bulk (multi-attachment) send: the item currently being sent
            // (0-based) out of [total], and a 0f..1f fraction for a determinate progress ring.
            // A single-attachment send leaves the defaults (index 0 / total 1).
            val index: Int = 0,
            val total: Int = 1,
            val fraction: Float = 0f,
        ) : Sending
    }

    data class Failure(val error: Throwable, val mediaInfos: List<MediaUploadInfo>) : SendActionState
    data object Done : SendActionState

    fun mediaUploadInfoList(): List<MediaUploadInfo>? = when (this) {
        is Sending.ReadyToUpload -> mediaInfos
        is Sending.Uploading -> mediaInfos
        is Failure -> mediaInfos
        else -> null
    }
}
