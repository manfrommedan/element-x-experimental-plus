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
import io.mockk.every
import io.mockk.mockk
import io.element.android.features.messages.impl.attachments.preview.AttachmentsPreviewEvent
import io.element.android.features.messages.impl.attachments.preview.AttachmentsPreviewPresenter
import io.element.android.features.messages.impl.attachments.preview.OnDoneListener
import io.element.android.features.messages.impl.attachments.video.MediaOptimizationSelectorState
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
import io.element.android.libraries.mediaupload.impl.DefaultMediaSender
import io.element.android.libraries.mediaupload.api.MediaSenderFactory
import io.element.android.libraries.mediaupload.test.FakeMediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.test.FakeMediaPreProcessor
import io.element.android.libraries.mediaviewer.test.viewer.aLocalMedia
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.fake.FakeTemporaryUriDeleter
import io.element.android.tests.testutils.consumeItemsUntilPredicate
import io.element.android.tests.testutils.consumeItemsUntilTimeout
import io.element.android.tests.testutils.lambda.lambdaError
import io.element.android.tests.testutils.test
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for per-slide caption preservation in the multi-attachment preview.
 *
 * Regression target: the previous snapshotFlow-based per-keystroke persister
 * raced with `update("")` from the incoming slide's LaunchedEffect and either
 * wiped freshly-typed captions or leaked text across slides. The commit-on-
 * swipe-away rewrite is supposed to make captions arrive on exactly the slides
 * the user typed on - no leakage onto slide 1, no loss on the slide they just
 * left.
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentsPreviewCaptionTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `typing on slide 2 and 5 of a 5-image batch captions only those two`() = runTest {
        // sendAllSequentially preserves the 0..N-1 attachment order, so the
        // captions list is indexed by slide position.
        val captionsInOrder = mutableListOf<String?>()
        val presenter = createMultiAttachmentPresenter(
            attachmentCount = 5,
            sendImage = recordCaptions(captionsInOrder),
        )
        presenter.test {
            val initial = awaitItem()
            initial.eventSink(AttachmentsPreviewEvent.NavigateToPage(1))
            val onSlide2 = consumeItemsUntilPredicate { it.currentIndex == 1 }.last()
            onSlide2.textEditorState.setMarkdown("two")
            onSlide2.eventSink(AttachmentsPreviewEvent.NavigateToPage(4))
            val onSlide5 = consumeItemsUntilPredicate { it.currentIndex == 4 }.last()
            onSlide5.textEditorState.setMarkdown("five")
            onSlide5.eventSink(AttachmentsPreviewEvent.SendAttachment)
            consumeItemsUntilTimeout(2.seconds)
            advanceUntilIdle()
            assertThat(captionsInOrder).containsExactly(null, "two", null, null, "five").inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `typing on slide 2 then swiping back without further typing keeps the caption on slide 2`() = runTest {
        val captionsInOrder = mutableListOf<String?>()
        val presenter = createMultiAttachmentPresenter(
            attachmentCount = 3,
            sendImage = recordCaptions(captionsInOrder),
        )
        presenter.test {
            val initial = awaitItem()
            initial.eventSink(AttachmentsPreviewEvent.NavigateToPage(1))
            val onSlide2 = consumeItemsUntilPredicate { it.currentIndex == 1 }.last()
            onSlide2.textEditorState.setMarkdown("two")
            onSlide2.eventSink(AttachmentsPreviewEvent.NavigateToPage(0))
            val back1 = consumeItemsUntilPredicate { it.currentIndex == 0 }.last()
            // Slide 1 was never typed on - editor must be empty.
            assertThat(back1.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEmpty()
            back1.eventSink(AttachmentsPreviewEvent.NavigateToPage(1))
            val back2 = consumeItemsUntilPredicate { it.currentIndex == 1 }.last()
            // Slide 2's draft must reload exactly as typed.
            assertThat(back2.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEqualTo("two")
            back2.eventSink(AttachmentsPreviewEvent.SendAttachment)
            consumeItemsUntilTimeout(2.seconds)
            advanceUntilIdle()
            assertThat(captionsInOrder).containsExactly(null, "two", null).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `typing then clearing on a slide removes that slide's draft`() = runTest {
        val captionsInOrder = mutableListOf<String?>()
        val presenter = createMultiAttachmentPresenter(
            attachmentCount = 3,
            sendImage = recordCaptions(captionsInOrder),
        )
        presenter.test {
            val initial = awaitItem()
            initial.eventSink(AttachmentsPreviewEvent.NavigateToPage(1))
            val onSlide2 = consumeItemsUntilPredicate { it.currentIndex == 1 }.last()
            onSlide2.textEditorState.setMarkdown("two")
            onSlide2.textEditorState.setMarkdown("")
            onSlide2.eventSink(AttachmentsPreviewEvent.NavigateToPage(2))
            val onSlide3 = consumeItemsUntilPredicate { it.currentIndex == 2 }.last()
            onSlide3.eventSink(AttachmentsPreviewEvent.SendAttachment)
            consumeItemsUntilTimeout(2.seconds)
            advanceUntilIdle()
            assertThat(captionsInOrder).containsExactly(null, null, null).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `captioning slides 2, 3, and 10 in a 10-image batch lands each caption on its own slide`() = runTest {
        // User's real scenario: large batch, captions on a few non-adjacent slides.
        // Each caption must land on the right slide, untouched slides stay null.
        val captionsInOrder = mutableListOf<String?>()
        val presenter = createMultiAttachmentPresenter(
            attachmentCount = 10,
            sendImage = recordCaptions(captionsInOrder),
        )
        presenter.test {
            val initial = awaitItem()
            initial.eventSink(AttachmentsPreviewEvent.NavigateToPage(1))
            val onSlide2 = consumeItemsUntilPredicate { it.currentIndex == 1 }.last()
            onSlide2.textEditorState.setMarkdown("two")
            onSlide2.eventSink(AttachmentsPreviewEvent.NavigateToPage(2))
            val onSlide3 = consumeItemsUntilPredicate { it.currentIndex == 2 }.last()
            // Editor must reload empty for slide 3 (no draft yet) - no leak from slide 2.
            assertThat(onSlide3.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEmpty()
            onSlide3.textEditorState.setMarkdown("three")
            onSlide3.eventSink(AttachmentsPreviewEvent.NavigateToPage(9))
            val onSlide10 = consumeItemsUntilPredicate { it.currentIndex == 9 }.last()
            assertThat(onSlide10.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEmpty()
            onSlide10.textEditorState.setMarkdown("ten")
            onSlide10.eventSink(AttachmentsPreviewEvent.SendAttachment)
            consumeItemsUntilTimeout(2.seconds)
            advanceUntilIdle()
            assertThat(captionsInOrder).containsExactly(
                null, "two", "three", null, null, null, null, null, null, "ten",
            ).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `user scenario - type on slide 1 and slide 3 of 6, send from slide 3, captions land correctly`() = runTest {
        // Exact user-reported scenario: 6 pictures, type '1' on the first slide,
        // jump to slide 3, type '3', send while still on slide 3. Bug report:
        // slide 1 ended up with caption '3' instead of '1'. Lock that down.
        val captionsInOrder = mutableListOf<String?>()
        val presenter = createMultiAttachmentPresenter(
            attachmentCount = 6,
            sendImage = recordCaptions(captionsInOrder),
        )
        presenter.test {
            val initial = awaitItem()
            assertThat(initial.currentIndex).isEqualTo(0)
            initial.textEditorState.setMarkdown("1")
            initial.eventSink(AttachmentsPreviewEvent.NavigateToPage(2))
            val onSlide3 = consumeItemsUntilPredicate { it.currentIndex == 2 }.last()
            assertThat(onSlide3.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEmpty()
            onSlide3.textEditorState.setMarkdown("3")
            onSlide3.eventSink(AttachmentsPreviewEvent.SendAttachment)
            consumeItemsUntilTimeout(2.seconds)
            advanceUntilIdle()
            assertThat(captionsInOrder).containsExactly("1", null, "3", null, null, null).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `user scenario - type on slide 1 and slide 6 of 6, send from slide 6`() = runTest {
        // The other concrete report: '1' on slide 1, '6' on slide 6, send from
        // slide 6. Result must be slide 1='1', slide 6='6'.
        val captionsInOrder = mutableListOf<String?>()
        val presenter = createMultiAttachmentPresenter(
            attachmentCount = 6,
            sendImage = recordCaptions(captionsInOrder),
        )
        presenter.test {
            val initial = awaitItem()
            initial.textEditorState.setMarkdown("1")
            initial.eventSink(AttachmentsPreviewEvent.NavigateToPage(5))
            val onSlide6 = consumeItemsUntilPredicate { it.currentIndex == 5 }.last()
            assertThat(onSlide6.textEditorState.messageMarkdown(FakePermalinkBuilder())).isEmpty()
            onSlide6.textEditorState.setMarkdown("6")
            onSlide6.eventSink(AttachmentsPreviewEvent.SendAttachment)
            consumeItemsUntilTimeout(2.seconds)
            advanceUntilIdle()
            assertThat(captionsInOrder).containsExactly("1", null, null, null, null, "6").inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `untouched slides never get a caption even when other slides are captioned`() = runTest {
        // Tightest regression: jump straight to slide 5, type "only five", send.
        // Slide 1 must NOT inherit anything.
        val captionsInOrder = mutableListOf<String?>()
        val presenter = createMultiAttachmentPresenter(
            attachmentCount = 5,
            sendImage = recordCaptions(captionsInOrder),
        )
        presenter.test {
            val initial = awaitItem()
            initial.eventSink(AttachmentsPreviewEvent.NavigateToPage(4))
            val onSlide5 = consumeItemsUntilPredicate { it.currentIndex == 4 }.last()
            onSlide5.textEditorState.setMarkdown("only five")
            onSlide5.eventSink(AttachmentsPreviewEvent.SendAttachment)
            consumeItemsUntilTimeout(2.seconds)
            advanceUntilIdle()
            assertThat(captionsInOrder).containsExactly(null, null, null, null, "only five").inOrder()
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
        // Robolectric isn't on this module, so Uri.parse returns null. Use mockk
        // Uris with distinct identities so the per-URI caption map keys correctly.
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
            sessionCoroutineScope = this,
            dispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true),
            mediaOptimizationSelectorPresenterFactory = FakeMediaOptimizationSelectorPresenterFactory(
                fakePresenter = {
                    MediaOptimizationSelectorState(
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
