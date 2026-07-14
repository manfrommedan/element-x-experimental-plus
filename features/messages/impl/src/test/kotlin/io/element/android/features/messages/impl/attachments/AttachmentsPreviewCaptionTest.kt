/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package io.element.android.features.messages.impl.attachments

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.attachments.preview.AttachmentsPreviewEvent
import io.element.android.features.messages.impl.attachments.preview.AttachmentsPreviewPresenter
import io.element.android.features.messages.impl.attachments.preview.OnDoneListener
import io.element.android.features.messages.impl.attachments.preview.imageeditor.AttachmentImageEditor
import io.element.android.features.messages.impl.attachments.preview.imageeditor.AttachmentImageEdits
import io.element.android.features.messages.impl.attachments.preview.imageeditor.EditedLocalMedia
import io.element.android.features.messages.impl.attachments.video.MediaOptimizationSelectorState
import io.element.android.features.messages.impl.attachments.video.VideoCompressionPresetSelector
import io.element.android.features.messages.impl.fixtures.aMediaAttachment
import io.element.android.features.messages.test.attachments.video.FakeMediaOptimizationSelectorPresenterFactory
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.media.ImageInfo
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.test.media.FakeMediaUploadHandler
import io.element.android.libraries.matrix.test.permalink.FakePermalinkBuilder
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfig
import io.element.android.libraries.mediaupload.api.MediaPreProcessor
import io.element.android.libraries.mediaupload.api.MediaSenderFactory
import io.element.android.libraries.mediaupload.impl.DefaultMediaSender
import io.element.android.libraries.mediaupload.test.FakeMediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.test.FakeMediaPreProcessor
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.test.viewer.aLocalMedia
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.consumeItemsUntilTimeout
import io.element.android.tests.testutils.fake.FakeTemporaryUriDeleter
import io.element.android.tests.testutils.test
import io.element.android.tests.testutils.testCoroutineDispatchers
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Multi-attachment send carries ONE shared caption attached to the FIRST attachment,
 * matching WhatsApp/Telegram batched-share semantics. The earlier per-slide caption
 * attempt had real-device timing bugs (caption swap across slides) that the unit
 * test layer couldn't reproduce.
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentsPreviewCaptionTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `multi-send with caption - caption attaches to the first attachment only`() = runTest {
        val captionsInOrder = mutableListOf<String?>()
        val presenter = createMultiAttachmentPresenter(
            attachmentCount = 5,
            sendImage = recordCaptions(captionsInOrder),
        )
        presenter.test {
            val initial = awaitItem()
            initial.textEditorState.setMarkdown("shared caption")
            initial.eventSink(AttachmentsPreviewEvent.SendAttachment)
            consumeItemsUntilTimeout(2.seconds)
            advanceUntilIdle()
            assertThat(captionsInOrder).containsExactly("shared caption", null, null, null, null).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `swiping between slides keeps the caption in the editor (single shared field)`() = runTest {
        // After removing per-slide drafts, the editor is one shared field for the
        // whole batch - swiping between slides must NOT reload or clear the text.
        // Asserted by: type, navigate, type more, then read back; expected concat.
        val captionsInOrder = mutableListOf<String?>()
        val presenter = createMultiAttachmentPresenter(
            attachmentCount = 5,
            sendImage = recordCaptions(captionsInOrder),
        )
        presenter.test {
            val initial = awaitItem()
            initial.textEditorState.setMarkdown("hel")
            initial.eventSink(AttachmentsPreviewEvent.NavigateToPage(2))
            // Drain navigation state emission(s).
            consumeItemsUntilTimeout(500.milliseconds)
            // Editor on slide 3 still has the text we typed on slide 1.
            val onSlide3 = initial // eventSink reference stable; check current text via the state object itself
            assertThat(onSlide3.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEqualTo("hel")
            onSlide3.textEditorState.setMarkdown("hello")
            onSlide3.eventSink(AttachmentsPreviewEvent.SendAttachment)
            consumeItemsUntilTimeout(2.seconds)
            advanceUntilIdle()
            // Caption attaches to FIRST attachment, regardless of which slide the user
            // was on at send time.
            assertThat(captionsInOrder).containsExactly("hello", null, null, null, null).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `multi-send without caption - no attachment gets a caption`() = runTest {
        val captionsInOrder = mutableListOf<String?>()
        val presenter = createMultiAttachmentPresenter(
            attachmentCount = 3,
            sendImage = recordCaptions(captionsInOrder),
        )
        presenter.test {
            val initial = awaitItem()
            initial.eventSink(AttachmentsPreviewEvent.SendAttachment)
            consumeItemsUntilTimeout(2.seconds)
            advanceUntilIdle()
            assertThat(captionsInOrder).containsExactly(null, null, null).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- helpers ---

    private fun recordCaptions(
        captions: MutableList<String?>,
    ): (File, File?, ImageInfo, String?, String?, EventId?) -> Result<FakeMediaUploadHandler> = { _, _, _, caption, _, _ ->
        captions += caption
        Result.success(FakeMediaUploadHandler())
    }

    private fun TestScope.createMultiAttachmentPresenter(
        attachmentCount: Int,
        sendImage: (File, File?, ImageInfo, String?, String?, EventId?) -> Result<FakeMediaUploadHandler>,
    ): AttachmentsPreviewPresenter {
        val attachments = (0 until attachmentCount).map { idx ->
            val uri: Uri = mockk("uri-$idx") {
                every { path } returns "/path/$idx"
            }
            aMediaAttachment(aLocalMedia(uri = uri))
        }
        val room: JoinedRoom = FakeJoinedRoom(
            liveTimeline = FakeTimeline().apply {
                sendImageLambda = sendImage
            },
        )
        val mediaPreProcessor: MediaPreProcessor = FakeMediaPreProcessor().apply {
            givenImageResult()
        }
        return AttachmentsPreviewPresenter(
            attachments = attachments,
            onDoneListener = OnDoneListener { /* multi-flow dismisses immediately - no-op is fine */ },
            mediaSenderFactory = MediaSenderFactory { mode ->
                DefaultMediaSender(
                    preProcessor = mediaPreProcessor,
                    room = room,
                    timelineMode = mode,
                    mediaOptimizationConfigProvider = {
                        MediaOptimizationConfig(compressImages = true, videoCompressionPreset = VideoCompressionPreset.STANDARD)
                    },
                )
            },
            permalinkBuilder = FakePermalinkBuilder(),
            temporaryUriDeleter = FakeTemporaryUriDeleter(deleteLambda = { /* no-op */ }),
            attachmentImageEditor = object : AttachmentImageEditor {
                override suspend fun canEdit(localMedia: LocalMedia) = false
                override suspend fun exportEdits(localMedia: LocalMedia, edits: AttachmentImageEdits) =
                    Result.failure<EditedLocalMedia>(NotImplementedError())
            },
            videoCompressionPresetSelector = VideoCompressionPresetSelector(),
            sessionCoroutineScope = this,
            dispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true),
            mediaOptimizationSelectorPresenterFactory = FakeMediaOptimizationSelectorPresenterFactory(
                fakePresenter = {
                    MediaOptimizationSelectorState(
                        index = 0,
                        maxUploadSize = AsyncData.Uninitialized,
                        videoSizeEstimations = AsyncData.Uninitialized,
                        isImageOptimizationEnabled = null,
                        selectedVideoPreset = null,
                        displayMediaSelectorViews = false,
                        displayVideoPresetSelectorDialog = false,
                        eventSink = {},
                    )
                }
            ),
            timelineMode = Timeline.Mode.Live,
            inReplyToEventId = null,
            mediaOptimizationConfigProvider = FakeMediaOptimizationConfigProvider(),
            localMediaFactory = io.element.android.libraries.mediaviewer.test.FakeLocalMediaFactory(
                localMediaUri = mockk("emptyUri") { every { path } returns "/empty" },
            ),
        )
    }
}
