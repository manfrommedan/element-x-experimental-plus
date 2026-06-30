/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.event

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.element.android.features.messages.impl.timeline.TimelineEvent
import io.element.android.features.messages.impl.timeline.components.layout.ContentAvoidingLayoutData
import io.element.android.features.messages.impl.timeline.di.LocalTimelineItemPresenterFactories
import io.element.android.features.messages.impl.timeline.di.rememberPresenter
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemAudioContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemEncryptedContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemEventContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemFileContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemLegacyCallInviteContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemLocationContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemPollContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemRedactedContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemRtcNotificationContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemStateContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemStickerContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextBasedContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemUnknownContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemVideoContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemVoiceContent
import io.element.android.features.messages.impl.timeline.model.event.ensureActiveLiveLocation
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.matrix.api.core.TransactionId
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.voiceplayer.api.VoiceMessageState
import io.element.android.wysiwyg.link.Link

/**
 * True iff the cancel-X overlay should be shown on a media bubble: there must be a
 * transactionId to address `Timeline.cancelSend(...)`, AND the item must still be
 * in any `Sending` substate (queued `Sending.Event` or active `Sending.MediaWithProgress`).
 * Extracted from the Composable so it can be unit-tested.
 */
internal fun canCancelUpload(transactionId: TransactionId?, localSendState: LocalEventSendState?): Boolean =
    transactionId != null && localSendState is LocalEventSendState.Sending

@Composable
fun TimelineItemEventContentView(
    content: TimelineItemEventContent,
    hideMediaContent: Boolean,
    showUrlPreviews: Boolean,
    onContentClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    onShowContentClick: () -> Unit,
    onLinkClick: (Link) -> Unit,
    onLinkLongClick: (Link) -> Unit,
    eventSink: (TimelineEvent.TimelineItemEvent) -> Unit,
    modifier: Modifier = Modifier,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit = {},
    localSendState: LocalEventSendState? = null,
    transactionId: TransactionId? = null,
) {
    // Show the cancel-X for ANY pending media (queued or actively uploading).
    // Queued items report Sending.Event (no progress) - we still render the
    // overlay so the whole batch can be aborted, not just the active one.
    val canCancelUpload = canCancelUpload(transactionId, localSendState)
    val mediaUploadProgress = localSendState as? LocalEventSendState.Sending.MediaWithProgress
    val onCancelUpload: (() -> Unit)? = remember(transactionId, canCancelUpload, eventSink) {
        if (canCancelUpload && transactionId != null) {
            { eventSink(TimelineEvent.CancelMediaUpload(transactionId)) }
        } else {
            null
        }
    }
    val presenterFactories = LocalTimelineItemPresenterFactories.current
    when (content) {
        is TimelineItemEncryptedContent -> TimelineItemEncryptedView(
            content = content,
            onContentLayoutChange = onContentLayoutChange,
            modifier = modifier
        )
        is TimelineItemRedactedContent -> TimelineItemRedactedView(
            content = content,
            onContentLayoutChange = onContentLayoutChange,
            modifier = modifier
        )
        is TimelineItemTextBasedContent -> TimelineItemTextView(
            content = content,
            showUrlPreviews = showUrlPreviews,
            modifier = modifier,
            onLinkClick = onLinkClick,
            onLinkLongClick = onLinkLongClick,
            onContentLayoutChange = onContentLayoutChange
        )
        is TimelineItemUnknownContent -> TimelineItemUnknownView(
            content = content,
            onContentLayoutChange = onContentLayoutChange,
            modifier = modifier
        )
        is TimelineItemLocationContent -> {
            TimelineItemLocationView(
                content = content.ensureActiveLiveLocation(),
                onStopLiveLocationClick = { eventSink(TimelineEvent.StopLiveLocationShare) },
                modifier = modifier
            )
        }
        is TimelineItemImageContent -> TimelineItemImageView(
            content = content,
            hideMediaContent = hideMediaContent,
            onContentClick = onContentClick,
            onLongClick = onLongClick,
            onShowContentClick = onShowContentClick,
            onLinkClick = onLinkClick,
            onLinkLongClick = onLinkLongClick,
            onContentLayoutChange = onContentLayoutChange,
            uploadProgress = mediaUploadProgress,
            onCancelUpload = onCancelUpload,
            modifier = modifier,
        )
        is TimelineItemStickerContent -> TimelineItemStickerView(
            content = content,
            hideMediaContent = hideMediaContent,
            onContentClick = onContentClick,
            onLongClick = onLongClick,
            onShowClick = onShowContentClick,
            modifier = modifier,
        )
        is TimelineItemVideoContent -> TimelineItemVideoView(
            content = content,
            hideMediaContent = hideMediaContent,
            onContentClick = onContentClick,
            onLongClick = onLongClick,
            onShowContentClick = onShowContentClick,
            onLinkClick = onLinkClick,
            onLinkLongClick = onLinkLongClick,
            onContentLayoutChange = onContentLayoutChange,
            uploadProgress = mediaUploadProgress,
            onCancelUpload = onCancelUpload,
            modifier = modifier
        )
        is TimelineItemFileContent -> TimelineItemFileView(
            content = content,
            onContentLayoutChange = onContentLayoutChange,
            uploadProgress = mediaUploadProgress,
            onCancelUpload = onCancelUpload,
            modifier = modifier
        )
        is TimelineItemAudioContent -> TimelineItemAudioView(
            content = content,
            onContentLayoutChange = onContentLayoutChange,
            uploadProgress = mediaUploadProgress,
            onCancelUpload = onCancelUpload,
            modifier = modifier
        )
        is TimelineItemLegacyCallInviteContent -> TimelineItemLegacyCallInviteView(modifier = modifier)
        is TimelineItemStateContent -> TimelineItemStateView(
            content = content,
            modifier = modifier
        )
        is TimelineItemPollContent -> TimelineItemPollView(
            content = content,
            eventSink = eventSink,
            modifier = modifier,
        )
        is TimelineItemVoiceContent -> {
            val presenter: Presenter<VoiceMessageState> = presenterFactories.rememberPresenter(content)
            TimelineItemVoiceView(
                state = presenter.present(),
                content = content,
                onContentLayoutChange = onContentLayoutChange,
                uploadProgress = mediaUploadProgress,
                onCancelUpload = onCancelUpload,
                modifier = modifier
            )
        }
        is TimelineItemRtcNotificationContent -> error("This shouldn't be rendered as the content of a bubble")
    }
}
