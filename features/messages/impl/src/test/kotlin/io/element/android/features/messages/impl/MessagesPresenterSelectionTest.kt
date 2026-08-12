/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package io.element.android.features.messages.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.location.test.FakeActiveLiveLocationShareManager
import io.element.android.features.messages.impl.actionlist.ActionListEvent
import io.element.android.features.messages.impl.actionlist.anActionListState
import io.element.android.features.messages.impl.crypto.identity.anIdentityChangeState
import io.element.android.features.messages.impl.link.aLinkState
import io.element.android.features.messages.impl.messagecomposer.MessageComposerState
import io.element.android.features.messages.impl.messagecomposer.aMessageComposerState
import io.element.android.features.messages.impl.pinned.banner.aLoadedPinnedMessagesBannerState
import io.element.android.features.messages.impl.selection.FakeSelectionMediaSaver
import io.element.android.features.messages.impl.selection.SelectionSaveCoordinator
import io.element.android.features.messages.impl.selection.TimelineSelectionState
import io.element.android.features.messages.impl.timeline.FakeMarkAsFullyRead
import io.element.android.features.messages.impl.timeline.MarkAsFullyRead
import io.element.android.features.messages.impl.timeline.TimelineController
import io.element.android.features.messages.impl.timeline.TimelineEvent
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.aTimelineState
import io.element.android.features.messages.impl.timeline.components.event.aGalleryItem
import io.element.android.features.messages.impl.timeline.components.event.aTimelineItemGalleryContent
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemRedactedContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemFileContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemTextContent
import io.element.android.features.messages.impl.timeline.protection.aTimelineProtectionState
import io.element.android.features.messages.test.timeline.FakeHtmlConverterProvider
import io.element.android.features.messages.test.timeline.voicemessages.composer.FakeDefaultVoiceMessageComposerPresenterFactory
import io.element.android.features.roomcall.api.aStandByCallState
import io.element.android.features.roommembermoderation.api.RoomMemberModerationState
import io.element.android.libraries.androidutils.clipboard.FakeClipboardHelper
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher
import io.element.android.libraries.emoji.api.recentemojis.AddRecentEmoji
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.permalink.PermalinkParser
import io.element.android.libraries.matrix.api.room.MessageEventType
import io.element.android.libraries.matrix.api.room.StateEventType
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.core.aBuildMeta
import io.element.android.libraries.matrix.test.encryption.FakeEncryptionService
import io.element.android.libraries.matrix.test.permalink.FakePermalinkParser
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.room.aRoomInfo
import io.element.android.libraries.matrix.test.room.powerlevels.FakeRoomPermissions
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.textcomposer.model.aTextEditorStateMarkdown
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.services.analytics.test.FakeAnalyticsService
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.consumeItemsUntilPredicate
import io.element.android.tests.testutils.consumeItemsUntilTimeout
import io.element.android.tests.testutils.lambda.lambdaError
import io.element.android.tests.testutils.testCoroutineDispatchers
import io.element.android.tests.testutils.testWithLifecycleOwner
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class MessagesPresenterSelectionTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    // --- Selection cap ---

    @Test
    fun `ToggleSelection stops adding at maxSelection`() = runTest {
        // Tapping more events than the cap keeps the selection at the limit; extra taps are ignored.
        val cap = TimelineSelectionState.MAX_SELECTION
        val events = (0 until cap + 5).map { aTimelineItemEvent(eventId = EventId("\$E-$it")) }
        val presenter = createMessagesPresenter(
            timelineItems = events.toImmutableList(),
        )
        presenter.testWithLifecycleOwner {
            val initial = awaitItem()
            events.forEach { initial.eventSink(MessagesEvent.ToggleSelection(it)) }
            val state = consumeItemsUntilPredicate { it.selectionState.isAtCap }.last()
            assertThat(state.selectionState.count).isEqualTo(cap)
            assertThat(state.selectionState.isAtCap).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Non-selectable events ---

    @Test
    fun `ToggleSelection ignores events that are not bulk-selectable`() = runTest {
        // State changes / redacted / call notifications are noise and must never enter the
        // selection. m1 + m2 are real messages, redacted is not selectable: final count is 2.
        val m1 = aTimelineItemEvent(eventId = EventId("\$M1"))
        val redacted = aTimelineItemEvent(eventId = EventId("\$RED"), content = TimelineItemRedactedContent)
        val m2 = aTimelineItemEvent(eventId = EventId("\$M2"))
        val presenter = createMessagesPresenter(
            timelineItems = persistentListOf(m1, redacted, m2),
        )
        presenter.testWithLifecycleOwner {
            val initial = awaitItem()
            initial.eventSink(MessagesEvent.ToggleSelection(m1))
            initial.eventSink(MessagesEvent.ToggleSelection(redacted))
            initial.eventSink(MessagesEvent.ToggleSelection(m2))
            val state = consumeItemsUntilPredicate { it.selectionState.count == 2 }.last()
            assertThat(state.selectionState.selectedIds).containsExactly(m1.eventId, m2.eventId)
            assertThat(state.selectionState.selectedIds).doesNotContain(redacted.eventId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `EnterSelection ignores non-bulk-selectable anchors and respects the cap`() = runTest {
        val redacted = aTimelineItemEvent(eventId = EventId("\$RED"), content = TimelineItemRedactedContent)
        val msg = aTimelineItemEvent(eventId = EventId("\$MSG"))
        val presenter = createMessagesPresenter(
            timelineItems = persistentListOf(redacted, msg),
        )
        presenter.testWithLifecycleOwner {
            val initial = awaitItem()
            // A non-selectable anchor must not enter selection...
            initial.eventSink(MessagesEvent.EnterSelection(redacted))
            // ...while a real message does.
            initial.eventSink(MessagesEvent.EnterSelection(msg))
            val state = consumeItemsUntilPredicate { it.selectionState.isActive }.last()
            assertThat(state.selectionState.selectedIds).containsExactly(msg.eventId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- BulkRedact ---

    @Test
    fun `BulkRedact - all success - no snackbar fires`() = runTest {
        val targetEvents = (0 until 3).map { aTimelineItemEvent(eventId = EventId("\$TGT-$it")) }
        val redactCalls = mutableListOf<EventOrTransactionId>()
        val timeline = FakeTimeline().apply {
            redactEventLambda = { eventOrTransactionId, _ ->
                redactCalls += eventOrTransactionId
                Result.success(Unit)
            }
        }
        val presenter = createMessagesPresenter(
            timeline = timeline,
            timelineItems = targetEvents.toImmutableList(),
        )
        presenter.testWithLifecycleOwner {
            val initial = awaitItem()
            targetEvents.forEach { initial.eventSink(MessagesEvent.ToggleSelection(it)) }
            val readied = consumeItemsUntilPredicate { it.selectionState.count == targetEvents.size }.last()
            assertThat(readied.selectionState.count).isEqualTo(targetEvents.size)
            readied.eventSink(MessagesEvent.BulkRedactSelected)
            // Drain emissions for ~1s real-time which also lets the background launch
            // through its delay-throttled loop (advanceUntilIdle is unreliable here
            // because the background coroutine plus snackbar dispatch arrive on
            // separate scheduler ticks the predicate-loop is the proven driver).
            val finalState = consumeItemsUntilTimeout(2.seconds).last()
            assertThat(redactCalls).hasSize(targetEvents.size)
            assertThat(finalState.selectionState.selectedIds).isEmpty()
            assertThat(finalState.snackbarMessage).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `BulkRedact - partial failure - snackbar fires with common_error`() = runTest {
        val targetEvents = (0 until 4).map { aTimelineItemEvent(eventId = EventId("\$TGT-$it")) }
        val timeline = FakeTimeline().apply {
            var idx = 0
            redactEventLambda = { _, _ ->
                val res = if (idx % 2 == 1) Result.failure(RuntimeException("boom")) else Result.success(Unit)
                idx += 1
                res
            }
        }
        val presenter = createMessagesPresenter(
            timeline = timeline,
            timelineItems = targetEvents.toImmutableList(),
        )
        presenter.testWithLifecycleOwner {
            val initial = awaitItem()
            targetEvents.forEach { initial.eventSink(MessagesEvent.ToggleSelection(it)) }
            val readied = consumeItemsUntilPredicate { it.selectionState.count == targetEvents.size }.last()
            readied.eventSink(MessagesEvent.BulkRedactSelected)
            advanceUntilIdle()
            val withSnackbar = consumeItemsUntilPredicate { it.snackbarMessage != null }.last()
            assertThat(withSnackbar.snackbarMessage?.messageResId).isEqualTo(CommonStrings.common_error)
            assertThat(withSnackbar.selectionState.selectedIds).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- BulkForward ---

    @Test
    fun `BulkForward orders by sentTimeMillis ASC regardless of tap order`() = runTest {
        // Three messages whose sentTimeMillis order (1000, 2000, 3000) is different from
        // both the timelineItems order (newest first below) and the user's tap order.
        val e1 = aTimelineItemEvent(eventId = EventId("\$E1")).copy(sentTimeMillis = 1000L)
        val e2 = aTimelineItemEvent(eventId = EventId("\$E2")).copy(sentTimeMillis = 2000L)
        val e3 = aTimelineItemEvent(eventId = EventId("\$E3")).copy(sentTimeMillis = 3000L)
        // timelineItems oldest-first; not the order we'll tap.
        val items = persistentListOf<TimelineItem>(e1, e2, e3)
        val forwarded = mutableListOf<EventId>()
        val navigator = FakeMessagesNavigator(
            onForwardEventClickLambda = { id -> forwarded += id },
        )
        val presenter = createMessagesPresenter(
            navigator = navigator,
            timelineItems = items,
        )
        presenter.testWithLifecycleOwner {
            val initial = awaitItem()
            initial.eventSink(MessagesEvent.ToggleSelection(e3))
            initial.eventSink(MessagesEvent.ToggleSelection(e1))
            initial.eventSink(MessagesEvent.ToggleSelection(e2))
            val readied = consumeItemsUntilPredicate { it.selectionState.count == 3 }.last()
            readied.eventSink(MessagesEvent.BulkForwardSelected)
            advanceUntilIdle()
            assertThat(forwarded).containsExactly(e1.eventId, e2.eventId, e3.eventId).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- BulkSave ---

    @Test
    fun `BulkSaveSelected writes every selected file and hands the room straight back`() = runTest {
        val photo = aTimelineItemEvent(eventId = EventId("\$S1"), content = aTimelineItemImageContent(filename = "photo.jpg")).copy(sentTimeMillis = 1000L)
        val doc = aTimelineItemEvent(eventId = EventId("\$S2"), content = aTimelineItemFileContent(fileName = "notes.pdf")).copy(sentTimeMillis = 2000L)
        val mediaSaver = FakeSelectionMediaSaver()
        val presenter = createMessagesPresenter(
            timelineItems = persistentListOf(doc, photo),
            selectionMediaSaver = mediaSaver,
        )
        presenter.testWithLifecycleOwner {
            val initial = awaitItem()
            initial.eventSink(MessagesEvent.ToggleSelection(doc))
            initial.eventSink(MessagesEvent.ToggleSelection(photo))
            val readied = consumeItemsUntilPredicate { it.selectionState.count == 2 }.last()
            readied.eventSink(MessagesEvent.BulkSaveSelected)
            // The selection ends the moment the work starts; the banner carries it from there. The
            // progress reaches the screen from the coordinator's flow, so wait for it rather than
            // expecting it in the same frame the selection clears.
            val started = consumeItemsUntilPredicate { it.selectionSaveProgress != null }.last()
            assertThat(started.selectionState.isActive).isFalse()
            assertThat(started.selectionSaveProgress?.total).isEqualTo(2)
            val finished = consumeItemsUntilPredicate { it.selectionSaveProgress == null }.last()
            assertThat(mediaSaver.savedFilenames).containsExactly("photo.jpg", "notes.pdf").inOrder()
            assertThat(finished.selectionSaveProgress).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `BulkSaveSelected counts an album as all of its pictures`() = runTest {
        val album = aTimelineItemEvent(
            eventId = EventId("\$S3"),
            content = aTimelineItemGalleryContent(
                items = listOf(aGalleryItem(filename = "one.jpg"), aGalleryItem(filename = "two.jpg")),
            ),
        )
        val mediaSaver = FakeSelectionMediaSaver()
        val presenter = createMessagesPresenter(
            timelineItems = persistentListOf(album),
            selectionMediaSaver = mediaSaver,
        )
        presenter.testWithLifecycleOwner {
            val initial = awaitItem()
            initial.eventSink(MessagesEvent.ToggleSelection(album))
            val readied = consumeItemsUntilPredicate { it.selectionState.count == 1 }.last()
            readied.eventSink(MessagesEvent.BulkSaveSelected)
            val started = consumeItemsUntilPredicate { it.selectionSaveProgress != null }.last()
            // One message, two files: the count a person sees has to be the count of files.
            assertThat(started.selectionSaveProgress?.total).isEqualTo(2)
            advanceUntilIdle()
            consumeItemsUntilPredicate { it.selectionSaveProgress == null }
            assertThat(mediaSaver.savedFilenames).containsExactly("one.jpg", "two.jpg")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `BulkSaveSelected does nothing when the selection holds a text message`() = runTest {
        val photo = aTimelineItemEvent(eventId = EventId("\$S4"), content = aTimelineItemImageContent())
        val text = aTimelineItemEvent(eventId = EventId("\$S5"), content = aTimelineItemTextContent(body = "hello"))
        val mediaSaver = FakeSelectionMediaSaver()
        val presenter = createMessagesPresenter(
            timelineItems = persistentListOf(photo, text),
            selectionMediaSaver = mediaSaver,
        )
        presenter.testWithLifecycleOwner {
            val initial = awaitItem()
            initial.eventSink(MessagesEvent.ToggleSelection(photo))
            initial.eventSink(MessagesEvent.ToggleSelection(text))
            val readied = consumeItemsUntilPredicate { it.selectionState.count == 2 }.last()
            readied.eventSink(MessagesEvent.BulkSaveSelected)
            advanceUntilIdle()
            // The action is not offered for such a selection, and firing it anyway saves nothing
            // and leaves the selection alone rather than half-doing the job.
            assertThat(mediaSaver.savedFilenames).isEmpty()
            assertThat(readied.selectionState.count).isEqualTo(2)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `CancelSelectionSave stops the batch and takes the banner away`() = runTest {
        val first = aTimelineItemEvent(eventId = EventId("\$S6"), content = aTimelineItemImageContent(filename = "one.jpg")).copy(sentTimeMillis = 1000L)
        val second = aTimelineItemEvent(eventId = EventId("\$S7"), content = aTimelineItemImageContent(filename = "two.jpg")).copy(sentTimeMillis = 2000L)
        val mediaSaver = FakeSelectionMediaSaver()
        val presenter = createMessagesPresenter(
            timelineItems = persistentListOf(first, second),
            selectionMediaSaver = mediaSaver,
        )
        presenter.testWithLifecycleOwner {
            val initial = awaitItem()
            initial.eventSink(MessagesEvent.ToggleSelection(first))
            initial.eventSink(MessagesEvent.ToggleSelection(second))
            val readied = consumeItemsUntilPredicate { it.selectionState.count == 2 }.last()
            readied.eventSink(MessagesEvent.BulkSaveSelected)
            val started = consumeItemsUntilPredicate { it.selectionSaveProgress != null }.last()
            started.eventSink(MessagesEvent.CancelSelectionSave)
            val stopped = consumeItemsUntilPredicate { it.selectionSaveProgress == null }.last()
            advanceUntilIdle()
            assertThat(stopped.selectionSaveProgress).isNull()
            // Whatever had already been written stays written; nothing new is.
            assertThat(mediaSaver.savedFilenames.size).isLessThan(2)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a save in flight is still shown after leaving the room and coming back`() = runTest {
        // Regression: with the progress held in the presenter's composition, walking out of the
        // room left the download running with no banner and no way to cancel it, and the guard
        // against starting a second batch went with it.
        val first = aTimelineItemEvent(eventId = EventId("\$S8"), content = aTimelineItemImageContent(filename = "one.jpg")).copy(sentTimeMillis = 1000L)
        val second = aTimelineItemEvent(eventId = EventId("\$S9"), content = aTimelineItemImageContent(filename = "two.jpg")).copy(sentTimeMillis = 2000L)
        val gate = CompletableDeferred<Unit>()
        val mediaSaver = FakeSelectionMediaSaver(beforeEachSave = { gate.await() })
        val coordinator = SelectionSaveCoordinator(
            selectionMediaSaver = mediaSaver,
            snackbarDispatcher = SnackbarDispatcher(),
            sessionCoroutineScope = backgroundScope,
        )
        val timelineItems = persistentListOf(first, second)

        createMessagesPresenter(
            timelineItems = timelineItems,
            selectionSaveCoordinator = coordinator,
        ).testWithLifecycleOwner {
            val initial = awaitItem()
            initial.eventSink(MessagesEvent.ToggleSelection(first))
            initial.eventSink(MessagesEvent.ToggleSelection(second))
            val readied = consumeItemsUntilPredicate { it.selectionState.count == 2 }.last()
            readied.eventSink(MessagesEvent.BulkSaveSelected)
            val started = consumeItemsUntilPredicate { it.selectionSaveProgress != null }.last()
            assertThat(started.selectionSaveProgress?.total).isEqualTo(2)
            cancelAndIgnoreRemainingEvents()
        }

        // Back into the room: a different presenter, a different composition, same batch.
        createMessagesPresenter(
            timelineItems = timelineItems,
            selectionSaveCoordinator = coordinator,
        ).testWithLifecycleOwner {
            val onReturn = consumeItemsUntilPredicate { it.selectionSaveProgress != null }.last()
            assertThat(onReturn.selectionSaveProgress?.total).isEqualTo(2)
            // And the cancel button works again, rather than pointing at a job nobody holds.
            onReturn.eventSink(MessagesEvent.CancelSelectionSave)
            val stopped = consumeItemsUntilPredicate { it.selectionSaveProgress == null }.last()
            assertThat(stopped.selectionSaveProgress).isNull()
            cancelAndIgnoreRemainingEvents()
        }

        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(mediaSaver.savedFilenames).isEmpty()
    }

    // --- BulkCopy ---

    @Test
    fun `BulkCopySelected joins bodies in chronological order and clears selection`() = runTest {
        // Bodies are copied in sentTime order, not tap order, joined by a blank line.
        val e1 = aTimelineItemEvent(eventId = EventId("\$C1"), content = aTimelineItemTextContent(body = "first")).copy(sentTimeMillis = 1000L)
        val e2 = aTimelineItemEvent(eventId = EventId("\$C2"), content = aTimelineItemTextContent(body = "second")).copy(sentTimeMillis = 2000L)
        val items = persistentListOf<TimelineItem>(e2, e1)
        val clipboardHelper = FakeClipboardHelper()
        val presenter = createMessagesPresenter(
            clipboardHelper = clipboardHelper,
            timelineItems = items,
        )
        presenter.testWithLifecycleOwner {
            val initial = awaitItem()
            initial.eventSink(MessagesEvent.ToggleSelection(e2))
            initial.eventSink(MessagesEvent.ToggleSelection(e1))
            val readied = consumeItemsUntilPredicate { it.selectionState.count == 2 }.last()
            readied.eventSink(MessagesEvent.BulkCopySelected)
            val finalState = consumeItemsUntilPredicate { !it.selectionState.isActive }.last()
            assertThat(clipboardHelper.clipboardContents).isEqualTo("first\n\nsecond")
            assertThat(finalState.selectionState.selectedIds).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `BulkCopySelected copies the captions of pictures along with the text`() = runTest {
        val text = aTimelineItemEvent(eventId = EventId("\$K1"), content = aTimelineItemTextContent(body = "look")).copy(sentTimeMillis = 1000L)
        val captioned = aTimelineItemEvent(
            eventId = EventId("\$K2"),
            content = aTimelineItemImageContent(caption = "at this"),
        ).copy(sentTimeMillis = 2000L)
        val clipboardHelper = FakeClipboardHelper()
        val presenter = createMessagesPresenter(
            clipboardHelper = clipboardHelper,
            timelineItems = persistentListOf(text, captioned),
        )
        presenter.testWithLifecycleOwner {
            val initial = awaitItem()
            initial.eventSink(MessagesEvent.ToggleSelection(text))
            initial.eventSink(MessagesEvent.ToggleSelection(captioned))
            val readied = consumeItemsUntilPredicate { it.selectionState.count == 2 }.last()
            readied.eventSink(MessagesEvent.BulkCopySelected)
            consumeItemsUntilPredicate { !it.selectionState.isActive }
            // A caption is text someone wrote, and leaving it behind would copy half the message.
            assertThat(clipboardHelper.clipboardContents).isEqualTo("look\n\nat this")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `BulkCopySelected with media-only selection writes nothing but still clears`() = runTest {
        val image = aTimelineItemEvent(eventId = EventId("\$IMG"), content = aTimelineItemImageContent())
        val clipboardHelper = FakeClipboardHelper()
        val presenter = createMessagesPresenter(
            clipboardHelper = clipboardHelper,
            timelineItems = persistentListOf(image),
        )
        presenter.testWithLifecycleOwner {
            val initial = awaitItem()
            initial.eventSink(MessagesEvent.ToggleSelection(image))
            val readied = consumeItemsUntilPredicate { it.selectionState.count == 1 }.last()
            readied.eventSink(MessagesEvent.BulkCopySelected)
            val finalState = consumeItemsUntilPredicate { !it.selectionState.isActive }.last()
            assertThat(clipboardHelper.clipboardContents).isNull()
            assertThat(finalState.selectionState.selectedIds).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Selection cap ---

    @Test
    fun `ToggleSelection silently rejects beyond the cap - no snackbar, the counter shows the limit`() = runTest {
        val cap = TimelineSelectionState.MAX_SELECTION
        val items = (0 until cap + 1).map { aTimelineItemEvent(eventId = EventId("\$E-$it")) }
        val presenter = createMessagesPresenter(
            timelineItems = items.toImmutableList(),
        )
        presenter.testWithLifecycleOwner {
            val initial = awaitItem()
            items.take(cap).forEach { initial.eventSink(MessagesEvent.ToggleSelection(it)) }
            val full = consumeItemsUntilPredicate { it.selectionState.count == cap }.last()
            assertThat(full.selectionState.isAtCap).isTrue()
            assertThat(full.snackbarMessage).isNull()
            // The 31st tap is rejected silently (no state change, no snackbar). Deselect an
            // existing one to force an observable emission and prove the 31st never made it in.
            full.eventSink(MessagesEvent.ToggleSelection(items.last()))
            full.eventSink(MessagesEvent.ToggleSelection(items.first()))
            val after = consumeItemsUntilPredicate { it.selectionState.count == cap - 1 }.last()
            assertThat(after.selectionState.selectedIds).doesNotContain(items.last().eventId)
            assertThat(after.snackbarMessage).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Helpers (mirrors MessagesPresenterTest.createMessagesPresenter, with timelineItems pass-through) ---

    private fun TestScope.createMessagesPresenter(
        coroutineDispatchers: CoroutineDispatchers = testCoroutineDispatchers(),
        timeline: Timeline = FakeTimeline(),
        joinedRoom: FakeJoinedRoom = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                roomPermissions = FakeRoomPermissions(
                    canSendState = { type ->
                        when (type) {
                            StateEventType.CallMember -> true
                            else -> lambdaError()
                        }
                    },
                    canSendMessage = { type ->
                        when (type) {
                            MessageEventType.RoomMessage -> true
                            MessageEventType.Reaction -> true
                            else -> lambdaError()
                        }
                    },
                    canRedactOther = true,
                    canRedactOwn = true,
                    canPinUnpin = true,
                ),
            ).apply {
                givenRoomInfo(aRoomInfo(id = roomId, name = ""))
            },
            liveTimeline = timeline,
            typingNoticeResult = { Result.success(Unit) },
        ),
        navigator: FakeMessagesNavigator = FakeMessagesNavigator(),
        clipboardHelper: FakeClipboardHelper = FakeClipboardHelper(),
        analyticsService: FakeAnalyticsService = FakeAnalyticsService(),
        timelineItems: ImmutableList<TimelineItem> = persistentListOf(),
        timelineEventSink: (TimelineEvent) -> Unit = {},
        permalinkParser: PermalinkParser = FakePermalinkParser(),
        messageComposerPresenter: Presenter<MessageComposerState> = Presenter {
            aMessageComposerState(
                textEditorState = aTextEditorStateMarkdown(initialText = "", initialFocus = false)
            )
        },
        roomMemberModerationPresenter: Presenter<RoomMemberModerationState> = Presenter {
            aRoomMemberModerationState()
        },
        encryptionService: FakeEncryptionService = FakeEncryptionService(),
        featureFlagService: FakeFeatureFlagService = FakeFeatureFlagService(),
        actionListEventSink: (ActionListEvent) -> Unit = {},
        addRecentEmoji: AddRecentEmoji = AddRecentEmoji { _ -> lambdaError() },
        markAsFullyRead: MarkAsFullyRead = FakeMarkAsFullyRead(),
        selectionMediaSaver: FakeSelectionMediaSaver = FakeSelectionMediaSaver(),
        // Passed in when a test needs two presenters to share one, the way leaving a room and
        // coming back gives the same session-scoped coordinator to a brand new composition.
        selectionSaveCoordinator: SelectionSaveCoordinator? = null,
        liveLocationShareManager: FakeActiveLiveLocationShareManager = FakeActiveLiveLocationShareManager(),
    ): MessagesPresenter {
        val snackbarDispatcher = SnackbarDispatcher()
        return MessagesPresenter(
            navigator = navigator,
            room = joinedRoom,
            composerPresenter = messageComposerPresenter,
            voiceMessageComposerPresenterFactory = FakeDefaultVoiceMessageComposerPresenterFactory(backgroundScope),
            timelinePresenter = { aTimelineState(timelineItems = timelineItems, eventSink = timelineEventSink) },
            timelineProtectionPresenter = { aTimelineProtectionState() },
            identityChangeStatePresenter = { anIdentityChangeState() },
            linkPresenter = { aLinkState() },
            actionListPresenter = { anActionListState(eventSink = actionListEventSink) },
            customReactionPresenter = { aCustomReactionState() },
            reactionSummaryPresenter = { aReactionSummaryState() },
            readReceiptBottomSheetPresenter = { aReadReceiptBottomSheetState() },
            pinnedMessagesBannerPresenter = { aLoadedPinnedMessagesBannerState() },
            roomCallStatePresenter = { aStandByCallState() },
            roomMemberModerationPresenter = roomMemberModerationPresenter,
            snackbarDispatcher = snackbarDispatcher,
            dispatchers = coroutineDispatchers,
            clipboardHelper = clipboardHelper,
            htmlConverterProvider = FakeHtmlConverterProvider(),
            buildMeta = aBuildMeta(),
            timelineController = TimelineController(joinedRoom, timeline),
            permalinkParser = permalinkParser,
            analyticsService = analyticsService,
            encryptionService = encryptionService,
            featureFlagService = featureFlagService,
            addRecentEmoji = addRecentEmoji,
            markAsFullyRead = markAsFullyRead,
            liveLocationShareManager = liveLocationShareManager,
            sessionCoroutineScope = backgroundScope,
            matrixClient = FakeMatrixClient(),
            selectionSaveCoordinator = selectionSaveCoordinator ?: SelectionSaveCoordinator(
                selectionMediaSaver = selectionMediaSaver,
                snackbarDispatcher = snackbarDispatcher,
                sessionCoroutineScope = backgroundScope,
            ),
        )
    }
}
