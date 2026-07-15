/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.attachments.preview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.R
import io.element.android.features.messages.impl.attachments.Attachment
import io.element.android.features.messages.impl.attachments.preview.error.sendAttachmentError
import io.element.android.features.messages.impl.attachments.preview.imageeditor.AttachmentImageEditorView
import io.element.android.features.messages.impl.attachments.video.MediaOptimizationSelectorEvent
import io.element.android.features.messages.impl.attachments.video.MediaOptimizationSelectorState
import io.element.android.features.messages.impl.attachments.video.VideoUploadEstimation
import io.element.android.libraries.core.bool.orFalse
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeVideo
import io.element.android.libraries.designsystem.components.ProgressDialog
import io.element.android.libraries.designsystem.components.ProgressDialogType
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.components.dialogs.AlertDialog
import io.element.android.libraries.designsystem.components.dialogs.ListDialog
import io.element.android.libraries.designsystem.components.dialogs.RetryDialog
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.modifiers.niceClickable
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.ElementPreviewDark
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Switch
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.designsystem.utils.CommonDrawables
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.api.local.LocalMediaRenderer
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import io.element.android.libraries.textcomposer.TextComposer
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import io.element.android.libraries.textcomposer.model.VoiceMessageState
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.libraries.ui.utils.formatter.rememberFileSizeFormatter
import io.element.android.wysiwyg.display.TextDisplay
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Ref: https://www.figma.com/design/zftpgS6LjiczobJZ1GUNpt/Updates-to-Media---File-Upload?node-id=51-3514
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentsPreviewView(
    state: AttachmentsPreviewState,
    localMediaRenderer: LocalMediaRenderer,
    modifier: Modifier = Modifier,
    onAddMoreClick: () -> Unit = {},
) {
    val canShowEditAction = when (state.sendActionState) {
        is SendActionState.Sending.Uploading -> false
        is SendActionState.Sending.Processing -> !state.sendActionState.displayProgress
        SendActionState.Done -> false
        else -> true
    }

    fun postSendAttachment() {
        state.eventSink(AttachmentsPreviewEvent.SendAttachment)
    }

    fun postCancel() {
        state.eventSink(AttachmentsPreviewEvent.CancelAndDismiss)
    }

    fun postClearSendState() {
        state.eventSink(AttachmentsPreviewEvent.CancelAndClearSendState)
    }

    fun postOpenImageEditor() {
        state.eventSink(AttachmentsPreviewEvent.OpenImageEditor)
    }

    fun postCloseImageEditor() {
        state.eventSink(AttachmentsPreviewEvent.CloseImageEditor)
    }

    fun postResetImageEditor() {
        state.eventSink(AttachmentsPreviewEvent.ResetImageEdits)
    }

    fun postApplyImageEdits() {
        state.eventSink(AttachmentsPreviewEvent.ApplyImageEdits)
    }

    BackHandler(enabled = state.sendActionState !is SendActionState.Sending.Uploading && state.sendActionState !is SendActionState.Done) {
        if (state.imageEditorState != null) {
            postCloseImageEditor()
        } else {
            postCancel()
        }
    }

    if (state.imageEditorState != null) {
        AttachmentImageEditorView(
            state = state.imageEditorState,
            onCropRectChange = { cropRect ->
                state.eventSink(AttachmentsPreviewEvent.UpdateImageCropRect(cropRect))
            },
            onRotateClick = { state.eventSink(AttachmentsPreviewEvent.RotateImageToTheLeft) },
            onFlipHorizontallyClick = { state.eventSink(AttachmentsPreviewEvent.FlipImageHorizontally) },
            onFlipVerticallyClick = { state.eventSink(AttachmentsPreviewEvent.FlipImageVertically) },
            onCancelClick = ::postCloseImageEditor,
            onResetClick = ::postResetImageEditor,
            onDoneClick = ::postApplyImageEdits,
            modifier = modifier,
        )
    } else {
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        BackButton(
                            onClick = ::postCancel,
                        )
                    },
                    title = {
                        // Bulk picker: show the "current / total" position; otherwise the upstream title.
                        if (state.totalCount > 1) {
                            Text(
                                modifier = Modifier.semantics {
                                    heading()
                                },
                                text = "${state.currentIndex + 1} / ${state.totalCount}",
                                style = ElementTheme.typography.fontBodyLgMedium,
                            )
                        } else {
                            Text(
                                modifier = Modifier.semantics {
                                    heading()
                                },
                                text = stringResource(R.string.screen_media_upload_preview_title),
                            )
                        }
                    },
                    actions = {
                        if (state.canEditImage && canShowEditAction) {
                            IconButton(
                                onClick = ::postOpenImageEditor,
                            ) {
                                Icon(
                                    imageVector = CompoundIcons.Crop(),
                                    contentDescription = stringResource(CommonStrings.action_edit),
                                )
                            }
                        }
                    }
                )
            }
        ) { paddingValues ->
            AttachmentPreviewContent(
                modifier = Modifier.padding(paddingValues),
                state = state,
                localMediaRenderer = localMediaRenderer,
                onSendClick = ::postSendAttachment,
                onAddMoreClick = onAddMoreClick,
            )
        }
    }
    AttachmentSendStateView(
        sendActionState = state.sendActionState,
        isApplyingImageEdits = state.isApplyingImageEdits,
        displayImageEditError = state.displayImageEditError,
        onDismissImageEditError = { state.eventSink(AttachmentsPreviewEvent.ClearImageEditError) },
        onDismissClick = ::postClearSendState,
        onRetryClick = ::postSendAttachment
    )
}

@Composable
private fun AttachmentSendStateView(
    sendActionState: SendActionState,
    isApplyingImageEdits: Boolean,
    displayImageEditError: Boolean,
    onDismissImageEditError: () -> Unit,
    onDismissClick: () -> Unit,
    onRetryClick: () -> Unit
) {
    when {
        isApplyingImageEdits -> {
            ProgressDialog(
                type = ProgressDialogType.Indeterminate,
                text = stringResource(CommonStrings.common_preparing),
                showCancelButton = false,
                onDismissRequest = {},
            )
        }
        displayImageEditError -> {
            AlertDialog(
                title = stringResource(CommonStrings.common_error),
                content = stringResource(CommonStrings.common_something_went_wrong_message),
                onDismiss = onDismissImageEditError,
            )
        }
        else -> when (sendActionState) {
            is SendActionState.Sending.Processing -> {
                if (sendActionState.displayProgress) {
                    if (sendActionState.total > 1) {
                        // Bulk send, preparing phase (compress/transcode): "Preparing N/total", cancellable.
                        // Show a determinate ring once real transcode progress arrives; until then (and for
                        // images, which report no incremental progress) keep an indeterminate spinner.
                        ProgressDialog(
                            type = if (sendActionState.fraction > 0f) {
                                ProgressDialogType.Determinate(sendActionState.fraction)
                            } else {
                                ProgressDialogType.Indeterminate
                            },
                            text = stringResource(
                                R.string.screen_attachments_preview_preparing_progress,
                                sendActionState.index + 1,
                                sendActionState.total,
                            ),
                            showCancelButton = true,
                            onDismissRequest = onDismissClick,
                        )
                    } else {
                        ProgressDialog(
                            type = ProgressDialogType.Indeterminate,
                            text = stringResource(CommonStrings.common_preparing),
                            showCancelButton = true,
                            onDismissRequest = onDismissClick,
                        )
                    }
                }
            }
            is SendActionState.Sending.Uploading -> {
                if (sendActionState.total > 1) {
                    // Bulk send, uploading phase: "Sending N/total". The upload has no exposed progress
                    // (unlike transcoding), so the ring stays indeterminate rather than faking a value.
                    ProgressDialog(
                        type = ProgressDialogType.Indeterminate,
                        text = stringResource(
                            R.string.screen_attachments_preview_sending_progress,
                            sendActionState.index + 1,
                            sendActionState.total,
                        ),
                        showCancelButton = true,
                        onDismissRequest = onDismissClick,
                    )
                } else {
                    ProgressDialog(
                        type = ProgressDialogType.Indeterminate,
                        text = stringResource(id = CommonStrings.common_sending),
                        showCancelButton = true,
                        onDismissRequest = onDismissClick,
                    )
                }
            }
            is SendActionState.Failure -> {
                RetryDialog(
                    content = stringResource(sendAttachmentError(sendActionState.error)),
                    onDismiss = onDismissClick,
                    onRetry = onRetryClick
                )
            }
            else -> Unit
        }
    }
}

@Composable
private fun AttachmentPreviewContent(
    state: AttachmentsPreviewState,
    localMediaRenderer: LocalMediaRenderer,
    onSendClick: () -> Unit,
    onAddMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        if (state.totalCount > 1) {
            val pagerState = rememberPagerState(initialPage = state.currentIndex) { state.totalCount }
            // Pager -> presenter: keep state.currentIndex in sync with user swipes.
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.currentPage }.collect { page ->
                    state.eventSink(AttachmentsPreviewEvent.NavigateToPage(page))
                }
            }
            // Presenter -> pager: jump to page when user taps a thumbnail (rare; usually swipe wins).
            LaunchedEffect(state.currentIndex) {
                if (pagerState.currentPage != state.currentIndex) {
                    pagerState.animateScrollToPage(state.currentIndex)
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { pageIndex ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val attachment = state.attachments[pageIndex]
                    if (attachment is Attachment.Media) {
                        localMediaRenderer.Render(attachment.localMedia)
                    }
                }
            }
            AttachmentsThumbnailStrip(state = state, onAddMoreClick = onAddMoreClick)
        } else {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                when (val attachment = state.attachment) {
                    is Attachment.Media -> {
                        localMediaRenderer.Render(attachment.localMedia)
                    }
                }
            }
        }
        val mediaInfo = (state.attachment as? Attachment.Media)?.localMedia?.info
        if (mediaInfo?.isImageAttachment() == true) {
            ImageOptimizationSelector(state.mediaOptimizationSelectorState)
        } else if (mediaInfo?.mimeType?.isMimeTypeVideo() == true) {
            VideoPresetSelector(state = state.mediaOptimizationSelectorState)
        }

        val sizeFormatter = rememberFileSizeFormatter()
        if (state.displayFileTooLargeError) {
            val maxFileUploadSize = state.mediaOptimizationSelectorState.maxUploadSize.dataOrNull()
            if (maxFileUploadSize != null) {
                val content = stringResource(CommonStrings.dialog_file_too_large_to_upload_subtitle, sizeFormatter.format(maxFileUploadSize, true))
                AlertDialog(
                    title = stringResource(CommonStrings.dialog_file_too_large_to_upload_title),
                    content = content,
                    onDismiss = { state.eventSink(AttachmentsPreviewEvent.CancelAndDismiss) },
                )
            }
        }

        AttachmentsPreviewBottomActions(
            state = state,
            onSendClick = onSendClick,
            modifier = Modifier
                .fillMaxWidth()
                .background(ElementTheme.colors.bgCanvasDefault)
                .height(IntrinsicSize.Min)
                .imePadding(),
        )
    }
}

@Composable
private fun AttachmentsThumbnailStrip(
    state: AttachmentsPreviewState,
    onAddMoreClick: () -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(state.attachments) { index, attachment ->
            val isCurrent = index == state.currentIndex
            val borderColor = if (isCurrent) ElementTheme.colors.iconAccentPrimary else ElementTheme.colors.borderDisabled
            Box(
                modifier = Modifier
                    .size(56.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(6.dp))
                        .border(width = if (isCurrent) 2.dp else 1.dp, color = borderColor, shape = RoundedCornerShape(6.dp))
                        .clickable { state.eventSink(AttachmentsPreviewEvent.NavigateToPage(index)) }
                ) {
                    if (attachment is Attachment.Media) {
                        AsyncImage(
                            model = attachment.localMedia.uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                // X badge for removing this item from the batch.
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .align(Alignment.TopEnd)
                        .clip(CircleShape)
                        .background(ElementTheme.colors.bgCanvasDefault)
                        .border(1.dp, ElementTheme.colors.borderInteractivePrimary, CircleShape)
                        .clickable { state.eventSink(AttachmentsPreviewEvent.RemoveAttachment(index)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = CompoundIcons.Close(),
                        contentDescription = stringResource(CommonStrings.action_remove),
                        modifier = Modifier.size(12.dp),
                        tint = ElementTheme.colors.iconPrimary,
                    )
                }
            }
        }
        // Trailing "add more" tile so the user can extend the batch without leaving the preview.
        item {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, ElementTheme.colors.borderDisabled, RoundedCornerShape(6.dp))
                    .clickable(onClick = onAddMoreClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = CompoundIcons.Plus(),
                    contentDescription = "Add more",
                    tint = ElementTheme.colors.iconSecondary,
                )
            }
        }
    }
}

@Composable
private fun ImageOptimizationSelector(state: MediaOptimizationSelectorState) {
    if (state.displayMediaSelectorViews == true) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .niceClickable {
                    state.isImageOptimizationEnabled?.let { value ->
                        state.eventSink(MediaOptimizationSelectorEvent.SelectImageOptimization(!value))
                    }
                }
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Text(
                modifier = Modifier
                    .weight(1f)
                    .align(Alignment.CenterVertically),
                text = stringResource(R.string.screen_media_upload_preview_optimize_image_quality_title),
                style = ElementTheme.typography.fontBodyLgRegular,
            )
            Switch(
                modifier = Modifier.height(32.dp),
                checked = state.isImageOptimizationEnabled.orFalse(),
                onCheckedChange = { value -> state.eventSink(MediaOptimizationSelectorEvent.SelectImageOptimization(value)) },
            )
        }
    }
}

@Composable
private fun VideoPresetSelector(
    state: MediaOptimizationSelectorState,
) {
    val videoPresets = state.videoSizeEstimations.dataOrNull()
    var selectedPreset by remember(state.selectedVideoPreset) { mutableStateOf(state.selectedVideoPreset) }

    val displayDialog = state.displayVideoPresetSelectorDialog

    val sizeFormatter = rememberFileSizeFormatter()

    if (state.displayMediaSelectorViews == true && videoPresets != null && state.selectedVideoPreset != null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .niceClickable { state.eventSink(MediaOptimizationSelectorEvent.OpenVideoPresetSelectorDialog) }
        ) {
            val estimation = videoPresets.find { it.preset == selectedPreset }
            val estimationMb = estimation?.sizeInBytes?.let { sizeFormatter.format(it, true) }
            val title = buildString {
                append(state.selectedVideoPreset.title())
                if (estimationMb != null) {
                    append(" ($estimationMb)")
                }
            }
            Text(text = title, style = ElementTheme.typography.fontBodyLgMedium)
            Text(
                text = stringResource(R.string.screen_media_upload_preview_change_video_quality_prompt),
                style = ElementTheme.typography.fontBodyLgMedium,
                color = ElementTheme.colors.textSecondary,
            )
        }
    }

    if (displayDialog) {
        VideoQualitySelectorDialog(
            selectedPreset = selectedPreset ?: VideoCompressionPreset.STANDARD,
            videoSizeEstimations = videoPresets ?: persistentListOf(),
            maxFileUploadSize = state.maxUploadSize.dataOrNull(),
            onSubmit = { preset ->
                selectedPreset = preset
                state.eventSink(MediaOptimizationSelectorEvent.SelectVideoPreset(preset))
            },
            onDismiss = { state.eventSink(MediaOptimizationSelectorEvent.DismissVideoPresetSelectorDialog) }
        )
    }
}

@Composable
private fun VideoQualitySelectorDialog(
    selectedPreset: VideoCompressionPreset,
    videoSizeEstimations: ImmutableList<VideoUploadEstimation>,
    maxFileUploadSize: Long?,
    onSubmit: (VideoCompressionPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    val sizeFormatter = rememberFileSizeFormatter()

    var localSelectedPreset by remember(selectedPreset) { mutableStateOf(selectedPreset) }
    val subtitlePartNoFileSize = stringResource(CommonStrings.dialog_video_quality_selector_subtitle_no_file_size)
    val subtitlePartWithFileSize = stringResource(CommonStrings.dialog_video_quality_selector_subtitle_file_size)
    val subtitle = remember(maxFileUploadSize) {
        buildString {
            append(subtitlePartNoFileSize)
            if (maxFileUploadSize != null) {
                append(String.format(subtitlePartWithFileSize, sizeFormatter.format(maxFileUploadSize, true)))
            }
        }
    }
    ListDialog(
        title = stringResource(CommonStrings.dialog_video_quality_selector_title),
        subtitle = subtitle,
        onSubmit = { onSubmit(localSelectedPreset) },
        onDismissRequest = onDismiss,
        applyPaddingToContents = false,
    ) {
        for (videoEstimation in videoSizeEstimations) {
            val preset = videoEstimation.preset
            val isSelected = preset == localSelectedPreset
            item(
                key = preset,
                contentType = preset,
            ) {
                val estimationMb = sizeFormatter.format(videoEstimation.sizeInBytes, true)
                val title = "${preset.title()} ($estimationMb)"
                ListItem(
                    headlineContent = {
                        Text(
                            text = title,
                            style = ElementTheme.typography.fontBodyLgMedium,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = preset.subtitle(),
                            style = ElementTheme.typography.fontBodyMdRegular,
                            color = ElementTheme.colors.textSecondary,
                        )
                    },
                    leadingContent = ListItemContent.RadioButton(
                        selected = isSelected,
                    ),
                    onClick = {
                        localSelectedPreset = preset
                    },
                    enabled = videoEstimation.canUpload,
                )
            }
        }
    }
}

@Composable
private fun AttachmentsPreviewBottomActions(
    state: AttachmentsPreviewState,
    onSendClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextComposer(
        modifier = modifier,
        state = state.textEditorState,
        voiceMessageState = VoiceMessageState.Idle,
        composerMode = MessageComposerMode.Attachment,
        onRequestFocus = {},
        onSendMessage = onSendClick,
        showTextFormatting = false,
        onResetComposerMode = {},
        onAddAttachment = {},
        onDismissTextFormatting = {},
        onVoiceRecorderEvent = {},
        onVoicePlayerEvent = {},
        onSendVoiceMessage = {},
        onDeleteVoiceMessage = {},
        onReceiveSuggestion = {},
        resolveMentionDisplay = { _, _ -> TextDisplay.Plain },
        resolveAtRoomMentionDisplay = { TextDisplay.Plain },
        onError = {},
        onTyping = {},
        onSelectRichContent = {},
    )
}

// Only preview in dark, dark theme is forced on the Node.
@Preview
@Composable
internal fun AttachmentsPreviewViewPreview(@PreviewParameter(AttachmentsPreviewStateProvider::class) state: AttachmentsPreviewState) = ElementPreviewDark {
    AttachmentsPreviewView(
        state = state,
        localMediaRenderer = object : LocalMediaRenderer {
            @Composable
            override fun Render(localMedia: LocalMedia) {
                Image(
                    painter = painterResource(id = CommonDrawables.sample_background),
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = null,
                )
            }
        }
    )
}

@PreviewsDayNight
@Composable
internal fun VideoQualitySelectorDialogPreview() {
    ElementPreview {
        VideoQualitySelectorDialog(
            selectedPreset = VideoCompressionPreset.STANDARD,
            videoSizeEstimations = persistentListOf(
                VideoUploadEstimation(VideoCompressionPreset.HIGH, 2_000_000, canUpload = false),
                VideoUploadEstimation(VideoCompressionPreset.STANDARD, 1_000_000, canUpload = true),
                VideoUploadEstimation(VideoCompressionPreset.LOW, 500_000, canUpload = true)
            ),
            maxFileUploadSize = 1_500_000,
            onSubmit = {},
            onDismiss = {},
        )
    }
}

@Composable
fun VideoCompressionPreset.title(): String {
    return stringResource(
        when (this) {
            VideoCompressionPreset.STANDARD -> CommonStrings.common_video_quality_standard
            VideoCompressionPreset.HIGH -> CommonStrings.common_video_quality_high
            VideoCompressionPreset.LOW -> CommonStrings.common_video_quality_low
        }
    )
}

@Composable
fun VideoCompressionPreset.subtitle(): String {
    return stringResource(
        when (this) {
            VideoCompressionPreset.STANDARD -> CommonStrings.common_video_quality_standard_description
            VideoCompressionPreset.HIGH -> CommonStrings.common_video_quality_high_description
            VideoCompressionPreset.LOW -> CommonStrings.common_video_quality_low_description
        }
    )
}
