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
import io.element.android.features.messages.impl.actionlist.ActionListState
import io.element.android.features.messages.impl.actionlist.anActionListState
import io.element.android.features.messages.impl.crypto.identity.anIdentityChangeState
import io.element.android.features.messages.impl.link.aLinkState
import io.element.android.features.messages.impl.messagecomposer.MessageComposerState
import io.element.android.features.messages.impl.messagecomposer.aMessageComposerState
import io.element.android.features.messages.impl.pinned.banner.aLoadedPinnedMessagesBannerState
import io.element.android.features.messages.impl.selection.TimelineSelectionState
import io.element.android.features.messages.impl.timeline.FakeMarkAsFullyRead
import io.element.android.features.messages.impl.timeline.MarkAsFullyRead
import io.element.android.features.messages.impl.timeline.TimelineController
import io.element.android.features.messages.impl.timeline.TimelineEvent
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.aTimelineState
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemRedactedContent
import io.element.android.features.messages.impl.timeline.protection.aTimelineProtectionState
import io.element.android.features.messages.test.timeline.FakeHtmlConverterProvider
import io.element.android.features.messages.test.timeline.voicemessages.composer.FakeDefaultVoiceMessageComposerPresenterFactory
import io.element.android.features.roomcall.api.aStandByCallState
import io.element.android.features.roommembermoderation.api.RoomMemberModerationState
import io.element.android.libraries.androidutils.clipboard.FakeClipboardHelper
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.permalink.PermalinkParser
import io.element.android.libraries.matrix.api.room.MessageEventType
import io.element.android.libraries.matrix.api.room.StateEventType
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import io.element.android.libraries.matrix.test.core.aBuildMeta
import io.element.android.libraries.matrix.test.encryption.FakeEncryptionService
import io.element.android.libraries.matrix.test.permalink.FakePermalinkParser
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.room.aRoomInfo
import io.element.android.libraries.matrix.test.room.powerlevels.FakeRoomPermissions
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.recentemojis.api.AddRecentEmoji
import io.element.android.libraries.textcomposer.model.aTextEditorStateMarkdown
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.services.analytics.test.FakeAnalyticsService
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.consumeItemsUntilPredicate
import io.element.android.tests.testutils.consumeItemsUntilTimeout
import io.element.android.tests.testutils.lambda.lambdaError
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.lambda.value
import io.element.android.tests.testutils.testCoroutineDispatchers
import io.element.android.tests.testutils.testWithLifecycleOwner
import kotlin.time.Duration.Companion.seconds
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class MessagesPresenterSelectionTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    // --- SelectAllVisible ---

    @Test
    fun `SelectAllVisible picks the NEWEST maxSelection events, not the oldest`() = runTest {
        // timelineItems is newest-first (TimelineItemsFactory emits items via the
        // diff cache walked in reverse). LazyColumn renders reverseLayout=true so
        // the first list item draws at the bottom of the screen - which is the
        // newest event. Cap = 30; oldest 10 in the tail of the list, newest 30 in
        // the head.
        val cap = TimelineSelectionState.MAX_SELECTION
        val newIds = (0 until cap).map { EventId("\$NEW-$it") }
        val oldIds = (0 until 10).map { EventId("\$OLD-$it") }
        val orderedNewestFirst = (newIds + oldIds).map { aTimelineItemEvent(eventId = it) }
        val presenter = createMessagesPresenter(
            timelineItems = orderedNewestFirst.toImmutableList(),
        )
        presenter.testWithLifecycleOwner {
            val state = awaitItem()
            state.eventSink(MessagesEvent.SelectAllVisible)
            val updated = consumeItemsUntilPredicate { it.selectionState.isActive }.last()
            assertThat(updated.selectionState.count).isEqualTo(cap)
            assertThat(updated.selectionState.selectedIds).containsExactlyElementsIn(newIds)
            assertThat(updated.selectionState.selectedIds.intersect(oldIds.toSet())).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SelectAllVisible skips redacted events`() = runTest {
        val live = (0 until 5).map { aTimelineItemEvent(eventId = EventId("\$LIVE-$it")) }
        val redacted = (0 until 3).map {
            aTimelineItemEvent(
                eventId = EventId("\$RED-$it"),
                content = TimelineItemRedactedContent,
            )
        }
        val presenter = createMessagesPresenter(
            timelineItems = (live + redacted).toImmutableList(),
        )
        presenter.testWithLifecycleOwner {
            val state = awaitItem()
            state.eventSink(MessagesEvent.SelectAllVisible)
            val updated = consumeItemsUntilPredicate { it.selectionState.isActive }.last()
            assertThat(updated.selectionState.count).isEqualTo(5)
            updated.selectionState.selectedIds.forEach { id ->
                assertThat(id.value).startsWith("\$LIVE-")
            }
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

    // --- Selection cap ---

    @Test
    fun `ToggleSelection rejects the 31st event and posts cap-reached snackbar`() = runTest {
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
            full.eventSink(MessagesEvent.ToggleSelection(items.last()))
            advanceUntilIdle()
            val afterReject = consumeItemsUntilPredicate { it.snackbarMessage != null }.last()
            assertThat(afterReject.selectionState.count).isEqualTo(cap)
            assertThat(afterReject.selectionState.selectedIds).doesNotContain(items.last().eventId)
            assertThat(afterReject.snackbarMessage?.messageResId).isEqualTo(R.string.screen_messages_selection_cap_reached)
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
        liveLocationShareManager: FakeActiveLiveLocationShareManager = FakeActiveLiveLocationShareManager(),
    ): MessagesPresenter {
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
            snackbarDispatcher = SnackbarDispatcher(),
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
        )
    }
}
