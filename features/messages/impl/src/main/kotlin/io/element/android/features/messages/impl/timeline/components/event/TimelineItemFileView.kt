/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.event

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.timeline.components.layout.ContentAvoidingLayoutData
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemFileContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemFileContentProvider
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.ui.strings.CommonStrings

@Composable
fun TimelineItemFileView(
    content: TimelineItemFileContent,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit,
    modifier: Modifier = Modifier,
    uploadProgress: LocalEventSendState.Sending.MediaWithProgress? = null,
    onCancelUpload: (() -> Unit)? = null,
) {
    Box(modifier = modifier) {
        TimelineItemAttachmentView(
            icon = CompoundIcons.Attachment(),
            iconContentDescription = stringResource(CommonStrings.common_file),
            filename = content.filename,
            fileExtensionAndSize = content.fileExtensionAndSize,
            caption = content.caption,
            onContentLayoutChange = onContentLayoutChange,
        )
        if (onCancelUpload != null) {
            MediaUploadOverlay(
                progress = uploadProgress,
                onCancel = onCancelUpload,
            )
        }
    }
}

@PreviewsDayNight
@Composable
internal fun TimelineItemFileViewPreview(@PreviewParameter(TimelineItemFileContentProvider::class) content: TimelineItemFileContent) {
    ElementTimelineItemPreview {
        TimelineItemFileView(
            content,
            onContentLayoutChange = {},
        )
    }
}
