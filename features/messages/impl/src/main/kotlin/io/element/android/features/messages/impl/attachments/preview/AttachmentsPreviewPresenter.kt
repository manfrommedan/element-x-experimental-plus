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
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import io.element.android.features.messages.impl.attachments.video.MediaOptimizationSelectorPresenter
import io.element.android.libraries.androidutils.file.TemporaryUriDeleter
import io.element.android.libraries.androidutils.file.safeDelete
import io.element.android.libraries.androidutils.hash.hash
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.coroutine.firstInstanceOf
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeImage
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
import io.element.android.libraries.mediaviewer.api.local.LocalMediaFactory
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import io.element.android.libraries.textcomposer.model.TextEditorState
import io.element.android.libraries.textcomposer.model.rememberMarkdownTextEditorState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

@AssistedInject
class AttachmentsPreviewPresenter(
    @Assisted private val attachments: List<Attachment>,
    @Assisted private val onDoneListener: OnDoneListener,
    @Assisted private val timelineMode: Timeline.Mode,
    @Assisted private val inReplyToEventId: EventId?,
    mediaSenderFactory: MediaSenderFactory,
    private val permalinkBuilder: PermalinkBuilder,
    private val temporaryUriDeleter: TemporaryUriDeleter,
    private val mediaOptimizationSelectorPresenterFactory: MediaOptimizationSelectorPresenter.Factory,
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

        val markdownTextEditorState = rememberMarkdownTextEditorState(initialText = null, initialFocus = false)
        val textEditorState by rememberUpdatedState(
            TextEditorState.Markdown(markdownTextEditorState, isRoomEncrypted = null)
        )

        val ongoingSendAttachmentJob = remember { mutableStateOf<Job?>(null) }

        var preprocessMediaJob by remember { mutableStateOf<Job?>(null) }

        // Live mutable list so Remove/AddMore events from the preview UI can rebuild the carousel.
        val attachmentList = remember { mutableStateListOf<Attachment>().apply { addAll(attachments) } }
        var currentIndex by remember { mutableStateOf(0) }
        // Guard against currentIndex falling past the end after a Remove.
        if (currentIndex > attachmentList.lastIndex) {
            currentIndex = attachmentList.lastIndex.coerceAtLeast(0)
        }
        val current = attachmentList[currentIndex]

        // Per-image caption store keyed by URI (stable across reorder; survives index shifts on Remove).
        val captions = remember { mutableStateMapOf<Uri, String>() }
        LaunchedEffect(currentIndex) {
            val media = attachmentList.getOrNull(currentIndex) as? Attachment.Media ?: return@LaunchedEffect
            val uri = media.localMedia.uri
            val loaded = captions[uri].orEmpty()
            // Load this slide's draft into the editor on entry.
            markdownTextEditorState.text.update(loaded, true)
            try {
                awaitCancellation()
            } finally {
                // Commit exactly once on swipe-away (or unmount). Snapshotting the
                // editor text per-keystroke caused a race: update("") from the
                // incoming slide's LE propagated to this slide's still-suspended
                // collector before cancellation aborted it, wiping freshly-typed
                // captions and occasionally leaking text across slides. Persisting
                // only at the cancellation boundary eliminates the cross-slide
                // contamination. Guarded by `text != loaded` so a pure visit
                // (no typing) leaves the existing draft untouched.
                val text = markdownTextEditorState.text.value().toString()
                if (text != loaded) {
                    if (text.isEmpty()) captions.remove(uri) else captions[uri] = text
                }
            }
        }
        val mediaAttachment = current as Attachment.Media
        val mediaOptimizationSelectorPresenter = remember(currentIndex) {
            mediaOptimizationSelectorPresenterFactory.create(mediaAttachment.localMedia)
        }
        val mediaOptimizationSelectorState by rememberUpdatedState(mediaOptimizationSelectorPresenter.present())

        val observableSendState = snapshotFlow { sendActionState.value }

        var displayFileTooLargeError by remember { mutableStateOf(false) }

        LaunchedEffect(mediaOptimizationSelectorState.displayMediaSelectorViews, currentIndex) {
            // Cancel any in-flight preprocess from a previous page; the upfront-preprocess optimization
            // is only relevant for single-attachment flow where the user lingers on one image.
            preprocessMediaJob?.cancel()
            sendActionState.value = SendActionState.Idle
            if (mediaOptimizationSelectorState.displayMediaSelectorViews == false && attachmentList.size == 1) {
                preprocessMediaJob = preProcessAttachment(
                    attachment = current,
                    mediaOptimizationConfig = mediaOptimizationConfigProvider.get(),
                    displayProgress = false,
                    sendActionState = sendActionState,
                )
            }
        }

        val maxUploadSize = mediaOptimizationSelectorState.maxUploadSize.dataOrNull()
        LaunchedEffect(maxUploadSize) {
            // Check file upload size if the media won't be processed for upload
            val isImageFile = mediaAttachment.localMedia.info.mimeType.isMimeTypeImage()
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

        fun cancelAndDismiss() {
            displayFileTooLargeError = false
            preprocessMediaJob?.cancel()
            mediaSender.cleanUp()
            ongoingSendAttachmentJob.value?.cancel()
            // Wipe the temporary URIs for every picked attachment so we don't leak storage.
            attachmentList.forEach { attach ->
                (attach as? Attachment.Media)?.let { temporaryUriDeleter.delete(it.localMedia.uri) }
            }
            sendActionState.value.mediaUploadInfo()?.let(::cleanUp)
            sendActionState.value = SendActionState.Done
            onDoneListener()
        }

        fun handleEvent(event: AttachmentsPreviewEvent) {
            when (event) {
                is AttachmentsPreviewEvent.NavigateToPage -> {
                    val target = event.index.coerceIn(0, attachmentList.lastIndex)
                    if (target != currentIndex) {
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
                    (removed as? Attachment.Media)?.let {
                        temporaryUriDeleter.delete(it.localMedia.uri)
                        captions.remove(it.localMedia.uri)
                    }
                    when {
                        currentIndex >= attachmentList.size -> currentIndex = attachmentList.lastIndex
                        event.index < currentIndex -> currentIndex -= 1
                    }
                }
                is AttachmentsPreviewEvent.AddMore -> {
                    if (event.picked.isEmpty()) return
                    val newAttachments = event.picked.map { (uri, mimeType) ->
                        Attachment.Media(
                            localMedia = localMediaFactory.createFromUri(
                                uri = uri,
                                mimeType = mimeType,
                                name = null,
                                formattedFileSize = null,
                            )
                        )
                    }
                    attachmentList.addAll(newAttachments)
                }
                is AttachmentsPreviewEvent.SendAttachment -> {
                    ongoingSendAttachmentJob.value = coroutineScope.launch {
                        val config = MediaOptimizationConfig(
                            compressImages = mediaOptimizationSelectorState.isImageOptimizationEnabled == true,
                            videoCompressionPreset = mediaOptimizationSelectorState.selectedVideoPreset ?: VideoCompressionPreset.STANDARD,
                        )
                        val caption = markdownTextEditorState.getMessageMarkdown(permalinkBuilder)
                            .takeIf { it.isNotEmpty() }

                        if (attachmentList.size == 1) {
                            // Single-attachment flow: keep the original preprocess-then-upload behaviour
                            // so the user sees existing Processing/Uploading progress dialogs.
                            if (mediaOptimizationSelectorState.displayMediaSelectorViews == true) {
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
                            val mediaUploadInfo = observableSendState.firstInstanceOf<SendActionState.Sending.ReadyToUpload>().mediaInfo
                            if (coroutineContext.isActive) {
                                onDoneListener()
                            }
                            sessionCoroutineScope.launch(dispatchers.io) {
                                sendPreProcessedMedia(
                                    mediaUploadInfo = mediaUploadInfo,
                                    caption = caption,
                                    sendActionState = sendActionState,
                                    dismissAfterSend = false,
                                    inReplyToEventId = inReplyToEventId,
                                )
                                mediaSender.cleanUp()
                            }
                        } else {
                            // Multi-attachment (bulk) flow: dismiss immediately, then send all in
                            // original 0..N-1 order from the session scope. Each image carries its own
                            // caption (entered while viewing that slide); the reply target lands on the
                            // first slide that has a caption, or slide 0 if none were typed.
                            // Flush current editor text into the captions map under the active slide URI.
                            (current as? Attachment.Media)?.let { media ->
                                val text = caption.orEmpty()
                                if (text.isEmpty()) captions.remove(media.localMedia.uri) else captions[media.localMedia.uri] = text
                            }
                            if (coroutineContext.isActive) {
                                onDoneListener()
                            }
                            val snapshot = attachmentList.toList()
                            val captionsSnapshot = captions.toMap()
                            val firstWithCaption = snapshot.indexOfFirst {
                                (it as? Attachment.Media)?.localMedia?.uri?.let { uri -> captionsSnapshot[uri]?.isNotEmpty() == true } == true
                            }.coerceAtLeast(0)
                            sessionCoroutineScope.launch(dispatchers.io) {
                                sendAllSequentially(
                                    items = snapshot,
                                    mediaOptimizationConfig = config,
                                    captionsByUri = captionsSnapshot,
                                    replyIndex = firstWithCaption,
                                )
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

                    val mediaUploadInfo = sendActionState.value.mediaUploadInfo()
                    sendActionState.value = if (mediaUploadInfo != null) {
                        SendActionState.Sending.ReadyToUpload(mediaUploadInfo)
                    } else {
                        SendActionState.Idle
                    }
                }
            }
        }

        return AttachmentsPreviewState(
            attachments = attachmentList.toImmutableList(),
            currentIndex = currentIndex,
            sendActionState = sendActionState.value,
            textEditorState = textEditorState,
            mediaOptimizationSelectorState = mediaOptimizationSelectorState,
            displayFileTooLargeError = displayFileTooLargeError,
            eventSink = ::handleEvent,
        )
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
                sendActionState.value = SendActionState.Sending.ReadyToUpload(mediaUploadInfo)
            },
            onFailure = {
                Timber.e(it, "Failed to pre-process media")
                if (it is CancellationException) {
                    throw it
                } else {
                    sendActionState.value = SendActionState.Failure(it, null)
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

    /**
     * Bulk-pick send. Iterates attachments in their original 0..N-1 order so the room timeline
     * preserves selection order. Caption + inReplyToEventId attach to the first message only,
     * matching WhatsApp / Telegram batched-share behaviour.
     *
     * Throttle between sends is provided by mediaSender's internal send pipeline; we add no extra
     * delay here. Failures are logged per-item but do not abort the batch.
     */
    private suspend fun sendAllSequentially(
        items: List<Attachment>,
        mediaOptimizationConfig: MediaOptimizationConfig,
        captionsByUri: Map<Uri, String>,
        replyIndex: Int,
    ) {
        for ((index, attach) in items.withIndex()) {
            val media = attach as? Attachment.Media ?: continue
            val perItemCaption = captionsByUri[media.localMedia.uri]?.takeIf { it.isNotEmpty() }
            runCatchingExceptions {
                mediaSender.sendMedia(
                    uri = media.localMedia.uri,
                    mimeType = media.localMedia.info.mimeType,
                    caption = perItemCaption,
                    inReplyToEventId = if (index == replyIndex) inReplyToEventId else null,
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
        sendActionState.value = SendActionState.Sending.Uploading(mediaUploadInfo)
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
                sendActionState.value = SendActionState.Failure(error, mediaUploadInfo)
            }
        }
    )
}
