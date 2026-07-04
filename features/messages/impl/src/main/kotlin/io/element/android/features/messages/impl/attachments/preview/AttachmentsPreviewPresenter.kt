/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.attachments.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.features.messages.impl.attachments.Attachment
import io.element.android.features.messages.impl.attachments.preview.imageeditor.AttachmentImageEditor
import io.element.android.features.messages.impl.attachments.preview.imageeditor.AttachmentImageEditorState
import io.element.android.features.messages.impl.attachments.preview.imageeditor.AttachmentImageEdits
import io.element.android.features.messages.impl.attachments.video.MediaOptimizationSelectorPresenter
import io.element.android.features.messages.impl.attachments.video.MediaOptimizationSelectorState
import io.element.android.features.messages.impl.attachments.video.VideoCompressionPresetSelector
import io.element.android.libraries.androidutils.file.TemporaryUriDeleter
import io.element.android.libraries.androidutils.file.safeDelete
import io.element.android.libraries.androidutils.hash.hash
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.coroutine.firstInstanceOf
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeVideo
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.permalink.PermalinkBuilder
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfig
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.api.MediaSenderFactory
import io.element.android.libraries.mediaupload.api.MediaUploadInfo
import io.element.android.libraries.mediaupload.api.allFiles
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.api.local.LocalMediaFactory
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import io.element.android.libraries.textcomposer.model.TextEditorState
import io.element.android.libraries.textcomposer.model.rememberMarkdownTextEditorState
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

@AssistedInject
class AttachmentsPreviewPresenter(
    @Assisted private val attachments: List<Attachment>,
    @Assisted private val onDoneListener: OnDoneListener,
    @Assisted private val timelineMode: Timeline.Mode,
    @Assisted private val inReplyToEventId: EventId?,
    mediaSenderFactory: MediaSenderFactory,
    private val permalinkBuilder: PermalinkBuilder,
    private val temporaryUriDeleter: TemporaryUriDeleter,
    private val attachmentImageEditor: AttachmentImageEditor,
    private val mediaOptimizationSelectorPresenterFactory: MediaOptimizationSelectorPresenter.Factory,
    private val videoCompressionPresetSelector: VideoCompressionPresetSelector,
    @SessionCoroutineScope private val sessionCoroutineScope: CoroutineScope,
    private val dispatchers: CoroutineDispatchers,
    private val mediaOptimizationConfigProvider: MediaOptimizationConfigProvider,
    private val localMediaFactory: LocalMediaFactory,
) : Presenter<AttachmentsPreviewState> {
    @AssistedFactory
    interface Factory {
        fun create(
            attachments: List<Attachment>,
            timelineMode: Timeline.Mode,
            onDoneListener: OnDoneListener,
            inReplyToEventId: EventId?,
        ): AttachmentsPreviewPresenter
    }

    private val mediaSender = mediaSenderFactory.create(timelineMode)

    @Composable
    override fun present(): AttachmentsPreviewState {
        val coroutineScope = rememberCoroutineScope()

        val sendActionState = remember {
            mutableStateOf<SendActionState>(SendActionState.Idle)
        }

        // Bulk picker (fork feature, behind the BulkAttachmentsPicker Labs flag at the picker entry):
        // a live mutable list so Remove/AddMore events from the preview UI can rebuild the carousel.
        // When only one attachment is present this behaves exactly like the upstream single-attachment flow.
        val attachmentList = remember { mutableStateListOf<Attachment>().apply { addAll(attachments) } }
        // Per-attachment image-editor bookkeeping (upstream image editor is a single-attachment feature;
        // these parallel lists keep it working per page so it always targets the currently shown image).
        val originalLocalMedias = remember {
            mutableStateListOf<LocalMedia>().apply {
                attachments.forEach { add((it as Attachment.Media).localMedia) }
            }
        }
        val appliedEditsList = remember {
            mutableStateListOf<AttachmentImageEdits>().apply { repeat(attachments.size) { add(AttachmentImageEdits()) } }
        }
        val editedTempFiles = remember {
            mutableStateListOf<File?>().apply { repeat(attachments.size) { add(null) } }
        }
        var currentIndex by remember { mutableIntStateOf(0) }
        // Guard against currentIndex falling past the end after a Remove.
        if (currentIndex > attachmentList.lastIndex) {
            currentIndex = attachmentList.lastIndex.coerceAtLeast(0)
        }
        val current = attachmentList[currentIndex]
        val originalLocalMedia = originalLocalMedias[currentIndex]

        var canEditImage by remember { mutableStateOf(originalLocalMedia.info.canEditImage()) }
        var imageEditorState by remember { mutableStateOf<AttachmentImageEditorState?>(null) }
        var isApplyingImageEdits by remember { mutableStateOf(false) }
        var displayImageEditError by remember { mutableStateOf(false) }

        val markdownTextEditorState = rememberMarkdownTextEditorState(initialText = null, initialFocus = false)
        val textEditorState by rememberUpdatedState(
            TextEditorState.Markdown(markdownTextEditorState, isRoomEncrypted = null)
        )

        val ongoingSendAttachmentJob = remember { mutableStateOf<Job?>(null) }

        var preprocessMediaJob by remember { mutableStateOf<Job?>(null) }

        val mediaAttachment = current as Attachment.Media
        val mediaOptimizationSelectorPresenter = remember(currentIndex) {
            mediaOptimizationSelectorPresenterFactory.create(
                index = currentIndex,
                localMedia = mediaAttachment.localMedia,
                sendAsFile = mediaAttachment.sendAsFile,
            )
        }
        val mediaOptimizationSelectorState by rememberUpdatedState(mediaOptimizationSelectorPresenter.present())

        val observableSendState = snapshotFlow { sendActionState.value }

        var displayFileTooLargeError by remember { mutableStateOf(false) }

        LaunchedEffect(
            mediaOptimizationSelectorState.displayMediaSelectorViews,
            mediaOptimizationSelectorState.videoSizeEstimations,
            currentIndex,
            current,
            imageEditorState,
            isApplyingImageEdits,
        ) {
            // Cancel any in-flight preprocess from a previous page; the upfront-preprocess optimization
            // is only relevant for the single-attachment flow where the user lingers on one image.
            preprocessMediaJob?.cancel()
            preprocessMediaJob = null
            sendActionState.value = SendActionState.Idle
            // If the media optimization selector is not displayed, we can pre-process the media to
            // prepare it for sending. Only done for the single-attachment flow; the bulk flow sends
            // each item through mediaSender.sendMedia which preprocesses internally.
            @Suppress("ComplexCondition")
            if (mediaOptimizationSelectorState.displayMediaSelectorViews == false &&
                attachmentList.size == 1 &&
                imageEditorState == null &&
                !isApplyingImageEdits) {
                if (mediaAttachment.localMedia.info.mimeType.isMimeTypeVideo() && mediaOptimizationSelectorState.videoSizeEstimations.dataOrNull() == null) {
                    Timber.d("Waiting for video size estimations to be able to select the best video compression preset before pre-processing the media")
                    return@LaunchedEffect
                }
                val config = getAutoPreprocessMediaOptimizationConfig(
                    mediaAttachment = mediaAttachment,
                    mediaOptimizationSelectorState = mediaOptimizationSelectorState,
                ) ?: return@LaunchedEffect
                preprocessMediaJob = coroutineScope.preProcessAttachment(
                    attachment = current,
                    mediaOptimizationConfig = config,
                    displayProgress = false,
                    sendActionState = sendActionState,
                )
            }
        }

        LaunchedEffect(currentIndex, originalLocalMedia) {
            canEditImage = originalLocalMedia.info.canEditImage() || attachmentImageEditor.canEdit(originalLocalMedia)
        }

        val maxUploadSize = mediaOptimizationSelectorState.maxUploadSize.dataOrNull()
        LaunchedEffect(maxUploadSize) {
            // Check file upload size if the media won't be processed for upload
            val isImageFile = mediaAttachment.localMedia.info.isImageAttachment()
            val isVideoFile = mediaAttachment.localMedia.info.mimeType.isMimeTypeVideo()
            if (maxUploadSize != null && !(isImageFile || isVideoFile)) {
                // If file size is not known, we're permissive and allow sending. The SDK will cancel the upload if needed.
                val fileSize = mediaAttachment.localMedia.info.fileSize ?: 0L
                if (maxUploadSize < fileSize) {
                    displayFileTooLargeError = true
                }
            }
        }

        val videoSizeEstimations = mediaOptimizationSelectorState.videoSizeEstimations.dataOrNull()
        LaunchedEffect(videoSizeEstimations) {
            if (videoSizeEstimations != null) {
                // Check if the video size estimations are too large for the max upload size
                displayFileTooLargeError = videoSizeEstimations.none { it.canUpload }
            }
        }

        // Invalidate any media pre-processed for a previous attachment set, so a send
        // never flushes a ReadyToUpload that no longer matches the current selection.
        fun resetPreparedSendState() {
            preprocessMediaJob?.cancel()
            preprocessMediaJob = null
            sendActionState.value.mediaUploadInfoList()?.forEach(::cleanUp)
            mediaSender.cleanUp()
            sendActionState.value = SendActionState.Idle
        }

        fun cancelAndDismiss() {
            displayFileTooLargeError = false
            displayImageEditError = false
            isApplyingImageEdits = false
            preprocessMediaJob?.cancel()
            preprocessMediaJob = null
            mediaSender.cleanUp()
            ongoingSendAttachmentJob.value?.cancel()
            // Wipe the temporary URIs for every picked attachment so we don't leak storage.
            attachmentList.forEach { attach ->
                (attach as? Attachment.Media)?.let { temporaryUriDeleter.delete(it.localMedia.uri) }
            }
            editedTempFiles.forEach { it?.safeDelete() }
            sendActionState.value.mediaUploadInfoList()?.forEach(::cleanUp)
            sendActionState.value = SendActionState.Done
            onDoneListener()
        }

        fun handleEvent(event: AttachmentsPreviewEvent) {
            when (event) {
                is AttachmentsPreviewEvent.NavigateToPage -> {
                    val target = event.index.coerceIn(0, attachmentList.lastIndex)
                    if (target != currentIndex) {
                        // Close any open editor when switching pages.
                        imageEditorState = null
                        currentIndex = target
                    }
                }
                is AttachmentsPreviewEvent.RemoveAttachment -> {
                    if (event.index !in attachmentList.indices) return
                    if (attachmentList.size <= 1) {
                        // Removing the last item is equivalent to cancelling the whole flow.
                        cancelAndDismiss()
                        return
                    }
                    val removed = attachmentList.removeAt(event.index)
                    originalLocalMedias.removeAt(event.index)
                    appliedEditsList.removeAt(event.index)
                    editedTempFiles.removeAt(event.index)?.safeDelete()
                    (removed as? Attachment.Media)?.let {
                        temporaryUriDeleter.delete(it.localMedia.uri)
                    }
                    when {
                        currentIndex >= attachmentList.size -> currentIndex = attachmentList.lastIndex
                        event.index < currentIndex -> currentIndex -= 1
                    }
                    // The set changed: drop any media that was pre-processed for the
                    // previous set, otherwise a following send can flush a stale
                    // ReadyToUpload for an attachment that is no longer here.
                    resetPreparedSendState()
                }
                is AttachmentsPreviewEvent.AddMore -> {
                    if (event.picked.isEmpty()) return
                    event.picked.forEach { (uri, mimeType) ->
                        val localMedia = localMediaFactory.createFromUri(
                            uri = uri,
                            mimeType = mimeType,
                            name = null,
                            formattedFileSize = null,
                        )
                        attachmentList.add(Attachment.Media(localMedia))
                        originalLocalMedias.add(localMedia)
                        appliedEditsList.add(AttachmentImageEdits())
                        editedTempFiles.add(null)
                    }
                    // Adding items turns a single-attachment (pre-processed) flow into a
                    // bulk one; discard the earlier ReadyToUpload so it can't be sent.
                    resetPreparedSendState()
                }
                is AttachmentsPreviewEvent.SendAttachment -> {
                    ongoingSendAttachmentJob.value = coroutineScope.launch {
                        val caption = markdownTextEditorState.getMessageMarkdown(permalinkBuilder)
                            .takeIf { it.isNotEmpty() }

                        if (attachmentList.size == 1) {
                            // Single-attachment flow: keep the upstream preprocess-then-upload behaviour
                            // so the user sees the existing Processing/Uploading progress dialogs.
                            if (mediaOptimizationSelectorState.displayMediaSelectorViews == true) {
                                val config = MediaOptimizationConfig(
                                    compressImages = mediaOptimizationSelectorState.isImageOptimizationEnabled == true,
                                    videoCompressionPreset = mediaOptimizationSelectorState.selectedVideoPreset ?: VideoCompressionPreset.STANDARD,
                                )
                                preprocessMediaJob = preProcessAttachment(
                                    attachment = current,
                                    mediaOptimizationConfig = config,
                                    displayProgress = true,
                                    sendActionState = sendActionState,
                                )
                            }
                            if (sendActionState.value is SendActionState.Sending.Processing) {
                                sendActionState.value = SendActionState.Sending.Processing(displayProgress = true)
                            }
                            val mediaUploadInfo = observableSendState.firstInstanceOf<SendActionState.Sending.ReadyToUpload>().mediaInfos.first()
                            val editedTempFileToDelete = editedTempFiles.getOrNull(0)
                            if (editedTempFiles.isNotEmpty()) editedTempFiles[0] = null
                            if (coroutineContext.isActive) {
                                onDoneListener()
                            }
                            sessionCoroutineScope.launch(dispatchers.io) {
                                try {
                                    sendPreProcessedMedia(
                                        mediaUploadInfo = mediaUploadInfo,
                                        caption = caption,
                                        sendActionState = sendActionState,
                                        dismissAfterSend = false,
                                        inReplyToEventId = inReplyToEventId,
                                    )
                                } finally {
                                    editedTempFileToDelete?.safeDelete()
                                    mediaSender.cleanUp()
                                }
                            }
                        } else {
                            // Multi-attachment (bulk) flow: WhatsApp-style single caption attached to the
                            // FIRST attachment of the batch. Reply target also lands on the first attachment.
                            // Items are sent in their original selection order (see sendAllSequentially).
                            val config = MediaOptimizationConfig(
                                compressImages = mediaOptimizationSelectorState.isImageOptimizationEnabled == true,
                                videoCompressionPreset = mediaOptimizationSelectorState.selectedVideoPreset ?: VideoCompressionPreset.STANDARD,
                            )
                            if (coroutineContext.isActive) {
                                onDoneListener()
                            }
                            val snapshot = attachmentList.toList()
                            val editedToDelete = editedTempFiles.toList()
                            editedTempFiles.indices.forEach { editedTempFiles[it] = null }
                            sessionCoroutineScope.launch(dispatchers.io) {
                                try {
                                    sendAllSequentially(
                                        items = snapshot,
                                        mediaOptimizationConfig = config,
                                        batchCaption = caption,
                                    )
                                } finally {
                                    editedToDelete.forEach { it?.safeDelete() }
                                }
                            }
                        }
                    }
                }
                AttachmentsPreviewEvent.CancelAndDismiss -> cancelAndDismiss()
                AttachmentsPreviewEvent.CancelAndClearSendState -> {
                    // Cancel media sending
                    ongoingSendAttachmentJob.value?.let {
                        it.cancel()
                        ongoingSendAttachmentJob.value = null
                    }

                    val mediaUploadInfos = sendActionState.value.mediaUploadInfoList()
                    sendActionState.value = if (mediaUploadInfos != null) {
                        SendActionState.Sending.ReadyToUpload(mediaUploadInfos)
                    } else {
                        SendActionState.Idle
                    }
                }
                AttachmentsPreviewEvent.OpenImageEditor -> {
                    val resolvedCanEditImage = canEditImage || originalLocalMedia.info.canEditImage()
                    if (resolvedCanEditImage) {
                        preprocessMediaJob?.cancel()
                        preprocessMediaJob = null
                        resetPreparedMedia(sendActionState)
                        imageEditorState = AttachmentImageEditorState(
                            localMedia = originalLocalMedia,
                            edits = appliedEditsList[currentIndex],
                            previewDebug = false,
                        )
                    }
                }
                AttachmentsPreviewEvent.CloseImageEditor -> {
                    imageEditorState = null
                }
                is AttachmentsPreviewEvent.UpdateImageCropRect -> {
                    val pendingState = imageEditorState ?: return
                    imageEditorState = pendingState.copy(
                        edits = pendingState.edits.copy(cropRect = event.cropRect)
                    )
                }
                AttachmentsPreviewEvent.RotateImageToTheLeft -> {
                    val pendingState = imageEditorState ?: return
                    imageEditorState = pendingState.copy(
                        edits = pendingState.edits.rotateAntiClockwise()
                    )
                }
                AttachmentsPreviewEvent.FlipImageHorizontally -> {
                    val pendingState = imageEditorState ?: return
                    imageEditorState = pendingState.copy(
                        edits = pendingState.edits.flipHorizontally()
                    )
                }
                AttachmentsPreviewEvent.FlipImageVertically -> {
                    val pendingState = imageEditorState ?: return
                    imageEditorState = pendingState.copy(
                        edits = pendingState.edits.flipVertically()
                    )
                }
                AttachmentsPreviewEvent.ResetImageEdits -> {
                    imageEditorState = imageEditorState?.copy(
                        edits = AttachmentImageEdits()
                    )
                }
                AttachmentsPreviewEvent.ApplyImageEdits -> {
                    val pendingState = imageEditorState ?: return
                    val index = currentIndex
                    if (!pendingState.edits.hasChanges) {
                        editedTempFiles[index]?.safeDelete()
                        editedTempFiles[index] = null
                        appliedEditsList[index] = pendingState.edits
                        attachmentList[index] = Attachment.Media(originalLocalMedias[index])
                        imageEditorState = null
                        resetPreparedMedia(sendActionState)
                        return
                    }
                    isApplyingImageEdits = true
                    displayImageEditError = false
                    coroutineScope.launch {
                        val result = withContext(dispatchers.io) {
                            attachmentImageEditor.exportEdits(
                                localMedia = originalLocalMedias[index],
                                edits = pendingState.edits,
                            )
                        }
                        result.fold(
                            onSuccess = { editedMedia ->
                                editedTempFiles[index]?.safeDelete()
                                editedTempFiles[index] = editedMedia.file
                                appliedEditsList[index] = pendingState.edits
                                attachmentList[index] = Attachment.Media(editedMedia.localMedia)
                                imageEditorState = null
                                resetPreparedMedia(sendActionState)
                            },
                            onFailure = {
                                Timber.e(it, "Failed to apply image edits")
                                displayImageEditError = true
                            }
                        )
                        isApplyingImageEdits = false
                    }
                }
                AttachmentsPreviewEvent.ClearImageEditError -> {
                    displayImageEditError = false
                }
            }
        }

        return AttachmentsPreviewState(
            attachments = attachmentList.toImmutableList(),
            currentIndex = currentIndex,
            imageEditorState = imageEditorState,
            canEditImage = canEditImage,
            isApplyingImageEdits = isApplyingImageEdits,
            displayImageEditError = displayImageEditError,
            sendActionState = sendActionState.value,
            textEditorState = textEditorState,
            mediaOptimizationSelectorState = mediaOptimizationSelectorState,
            displayFileTooLargeError = displayFileTooLargeError,
            eventSink = ::handleEvent,
        )
    }

    private suspend fun getAutoPreprocessMediaOptimizationConfig(
        mediaAttachment: Attachment.Media,
        mediaOptimizationSelectorState: MediaOptimizationSelectorState,
    ): MediaOptimizationConfig? {
        return if (mediaAttachment.sendAsFile) {
            // If we're sending the media as a file, we can skip image compression and we should select the highest video compression preset that still fits
            // the upload limit (if the estimations are available)
            val videoCompressionPreset = videoCompressionPresetSelector.selectBestVideoPreset(
                expectedVideoPreset = VideoCompressionPreset.HIGH,
                videoSizeEstimations = mediaOptimizationSelectorState.videoSizeEstimations,
            ).dataOrNull() ?: VideoCompressionPreset.HIGH

            MediaOptimizationConfig(
                compressImages = false,
                videoCompressionPreset = videoCompressionPreset,
            )
        } else {
            // Otherwise, we just rely on the user preferences for media optimization
            mediaOptimizationConfigProvider.get()
        }
    }

    private fun CoroutineScope.preProcessAttachment(
        attachment: Attachment,
        mediaOptimizationConfig: MediaOptimizationConfig,
        displayProgress: Boolean,
        sendActionState: MutableState<SendActionState>,
    ) = launch(dispatchers.io) {
        when (attachment) {
            is Attachment.Media -> {
                preProcessMedia(
                    mediaAttachment = attachment,
                    mediaOptimizationConfig = mediaOptimizationConfig,
                    displayProgress = displayProgress,
                    sendActionState = sendActionState,
                )
            }
        }
    }

    private suspend fun preProcessMedia(
        mediaAttachment: Attachment.Media,
        mediaOptimizationConfig: MediaOptimizationConfig,
        displayProgress: Boolean,
        sendActionState: MutableState<SendActionState>,
    ) {
        sendActionState.value = SendActionState.Sending.Processing(displayProgress = displayProgress)
        mediaSender.preProcessMedia(
            uri = mediaAttachment.localMedia.uri,
            mimeType = mediaAttachment.localMedia.info.mimeType,
            mediaOptimizationConfig = mediaOptimizationConfig,
        ).fold(
            onSuccess = { mediaUploadInfo ->
                Timber.d("Media ${mediaUploadInfo.file.path.orEmpty().hash()} finished processing, it's now ready to upload")
                sendActionState.value = SendActionState.Sending.ReadyToUpload(listOf(mediaUploadInfo))
            },
            onFailure = {
                Timber.e(it, "Failed to pre-process media")
                if (it is CancellationException) {
                    throw it
                } else {
                    sendActionState.value = SendActionState.Failure(it, emptyList())
                }
            }
        )
    }

    private fun cleanUp(
        mediaUploadInfo: MediaUploadInfo,
    ) {
        mediaUploadInfo.allFiles().forEach { file ->
            file.safeDelete()
        }
    }

    private fun resetPreparedMedia(sendActionState: MutableState<SendActionState>) {
        sendActionState.value.mediaUploadInfoList()?.forEach(::cleanUp)
        mediaSender.cleanUp()
        sendActionState.value = SendActionState.Idle
    }

    /**
     * Bulk-pick send. Iterates attachments in their original 0..N-1 order so the room timeline
     * preserves selection order. The single shared [batchCaption] and [inReplyToEventId] attach
     * to the first attachment only - standard batched-share semantics.
     *
     * Throttle between sends is provided by mediaSender's internal send pipeline; we add no extra
     * delay here. Failures are logged per-item but do not abort the batch.
     */
    private suspend fun sendAllSequentially(
        items: List<Attachment>,
        mediaOptimizationConfig: MediaOptimizationConfig,
        batchCaption: String?,
    ) {
        for ((index, attach) in items.withIndex()) {
            val media = attach as? Attachment.Media ?: continue
            runCatchingExceptions {
                mediaSender.sendMedia(
                    uri = media.localMedia.uri,
                    mimeType = media.localMedia.info.mimeType,
                    caption = if (index == 0) batchCaption else null,
                    inReplyToEventId = if (index == 0) inReplyToEventId else null,
                    mediaOptimizationConfig = mediaOptimizationConfig,
                ).getOrThrow()
            }.onFailure { cause ->
                Timber.e(cause, "Failed to send bulk attachment ${index + 1}/${items.size}")
                if (cause is CancellationException) throw cause
            }
            temporaryUriDeleter.delete(media.localMedia.uri)
        }
        mediaSender.cleanUp()
    }

    private suspend fun sendPreProcessedMedia(
        mediaUploadInfo: MediaUploadInfo,
        caption: String?,
        sendActionState: MutableState<SendActionState>,
        dismissAfterSend: Boolean,
        inReplyToEventId: EventId?,
    ) = runCatchingExceptions {
        sendActionState.value = SendActionState.Sending.Uploading(listOf(mediaUploadInfo))
        mediaSender.sendPreProcessedMedia(
            mediaUploadInfo = mediaUploadInfo,
            caption = caption,
            formattedCaption = null,
            inReplyToEventId = inReplyToEventId,
        ).getOrThrow()
    }.fold(
        onSuccess = {
            cleanUp(mediaUploadInfo)
            // Reset the sendActionState to ensure that dialog is closed before the screen
            sendActionState.value = SendActionState.Done

            if (dismissAfterSend) {
                onDoneListener()
            }
        },
        onFailure = { error ->
            Timber.e(error, "Failed to send attachment")
            if (error is CancellationException) {
                throw error
            } else {
                sendActionState.value = SendActionState.Failure(error, listOf(mediaUploadInfo))
            }
        }
    )
}
