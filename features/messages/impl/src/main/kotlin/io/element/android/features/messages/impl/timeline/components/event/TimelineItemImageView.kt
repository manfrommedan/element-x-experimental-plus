/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.event

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.components.ATimelineItemEventRow
import io.element.android.features.messages.impl.timeline.model.TimelineItemGroupPosition
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemImageContentPreviewParam
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemImageContent
import io.element.android.features.messages.impl.timeline.protection.ProtectedView
import io.element.android.features.messages.impl.timeline.protection.coerceRatioWhenHidingContent
import io.element.android.features.messages.impl.timeline.util.handleAsyncImageStateChange
import io.element.android.libraries.designsystem.components.blurhash.blurHashBackground
import io.element.android.libraries.designsystem.components.media.LocalAutoLoadMedia
import io.element.android.libraries.designsystem.modifiers.onKeyboardContextMenuAction
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.matrix.ui.media.contentvalidation.ContentValidationState
import io.element.android.libraries.matrix.ui.media.contentvalidation.LocalEventContentValidationState
import io.element.android.libraries.matrix.ui.media.contentvalidation.NoopContentValidationState
import io.element.android.libraries.matrix.ui.media.contentvalidation.NoopEventContentValidationCache
import io.element.android.libraries.matrix.ui.media.contentvalidation.collectOverallState
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.libraries.ui.utils.a11y.isTalkbackActive

@Composable
fun TimelineItemImageView(
    content: TimelineItemImageContent,
    hideMediaContent: Boolean,
    onContentClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    onShowContentClick: () -> Unit,
    contentValidationState: ContentValidationState,
    modifier: Modifier = Modifier,
    uploadProgress: LocalEventSendState.Sending.MediaWithProgress? = null,
    onCancelUpload: (() -> Unit)? = null,
) {
    val a11yLabel = stringResource(CommonStrings.common_image)
    val description = content.caption?.let { "$a11yLabel: $it" } ?: a11yLabel
    Column(modifier = modifier.wrapContentWidth(Alignment.CenterHorizontally)) {
        val containerModifier = if (content.showCaption) {
            Modifier.clip(RoundedCornerShape(10.dp))
        } else {
            Modifier
        }

        val eventContentValidation by contentValidationState.collectOverallState()
        val isContentBeingValidated = !eventContentValidation.isValidated()
        TimelineItemAspectRatioBox(
            modifier = containerModifier
                .blurHashBackground(content.blurhash, alpha = 0.9f)
                .align(Alignment.CenterHorizontally),
            aspectRatio = coerceRatioWhenHidingContent(content.aspectRatio, hideMediaContent),
        ) {
            if (isContentBeingValidated) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                ProtectedView(
                    hideContent = hideMediaContent,
                    onShowClick = onShowContentClick,
                ) {
                    val autoLoad = LocalAutoLoadMedia.current
                    var userTapped by rememberSaveable { mutableStateOf(false) }
                    val networkAllowed = autoLoad || userTapped || onCancelUpload != null
                    // Always issue the request; the fetcher honors allowNetwork to
                    // refuse new fetches in wifi-only mode, but Coil's memory + disk
                    // caches are checked first - so previously loaded thumbnails
                    // render instantly even on mobile data. Tap-to-download only
                    // appears on the painter's Error state (cache miss + can't fetch).
                    val model = remember(content.thumbnailMediaRequestData, networkAllowed) {
                        if (networkAllowed) {
                            content.thumbnailMediaRequestData
                        } else {
                            content.thumbnailMediaRequestData.copy(allowNetwork = false)
                        }
                    }
                    var painterState by remember(model) {
                        mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)
                    }
                    val isLoaded = painterState is AsyncImagePainter.State.Success
                    val showTapToDownload = !networkAllowed && painterState is AsyncImagePainter.State.Error
                    if (!showTapToDownload) {
                        AsyncImage(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (onCancelUpload != null) Modifier.blur(6.dp) else Modifier)
                                .then(if (isLoaded) Modifier.background(Color.White) else Modifier)
                                .then(
                                    if (!isTalkbackActive() && onContentClick != null) {
                                        Modifier
                                            .combinedClickable(
                                                onClick = onContentClick,
                                                onLongClick = onLongClick,
                                            )
                                            .onKeyboardContextMenuAction(onLongClick)
                                    } else {
                                        Modifier
                                    }
                                ),
                            model = model,
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.Center,
                            contentDescription = description,
                            onState = { state ->
                                painterState = state
                                val url = content.thumbnailMediaRequestData.source?.safeUrl
                                if (url != null) {
                                    handleAsyncImageStateChange(
                                        state = state,
                                        onLoaded = {},
                                        updateContentValidationState = { contentValidationState.update(url, it) },
                                    )
                                }
                            },
                        )
                    } else {
                        TapToDownloadOverlay(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { userTapped = true },
                                    onLongClick = onLongClick,
                                ),
                        )
                    }
                    if (onCancelUpload != null) {
                        MediaUploadOverlay(
                            progress = uploadProgress,
                            onCancel = onCancelUpload,
                        )
                    }
                }
            }
        }
    }
}

@PreviewsDayNight
@Composable
internal fun TimelineItemImageViewPreview(@PreviewParameter(TimelineItemImageContentPreviewParam::class) content: TimelineItemImageContent) = ElementPreview {
    TimelineItemImageView(
        content = content,
        hideMediaContent = false,
        onShowContentClick = {},
        onContentClick = {},
        onLongClick = {},
        contentValidationState = NoopContentValidationState(),
    )
}

@PreviewsDayNight
@Composable
internal fun TimelineItemImageViewHideMediaContentPreview() = ElementPreview {
        TimelineItemImageView(
            content = aTimelineItemImageContent(),
            hideMediaContent = true,
            onShowContentClick = {},
            onContentClick = {},
            onLongClick = {},
            contentValidationState = NoopContentValidationState(),
        )
}

@PreviewsDayNight
@Composable
internal fun ATimelineItemEventRowPreview() = ElementPreview {
    CompositionLocalProvider(LocalEventContentValidationState provides NoopEventContentValidationCache()) {
        Column {
            sequenceOf(false, true).forEach { isMine ->
                ATimelineItemEventRow(
                    event = aTimelineItemEvent(
                        isMine = isMine,
                        content = aTimelineItemImageContent(
                            filename = "image.jpg",
                            caption = "A long caption that may wrap into several lines",
                            width = 40,
                            height = 20,
                            aspectRatio = 40f / 20f,
                        ),
                        groupPosition = TimelineItemGroupPosition.Last,
                    ),
                )
            }
            ATimelineItemEventRow(
                event = aTimelineItemEvent(
                    isMine = false,
                    content = aTimelineItemImageContent(
                        filename = "image.jpg",
                        caption = "Narrow image with null aspectRatio",
                        width = 80,
                        height = 150,
                        aspectRatio = null,
                    ),
                    groupPosition = TimelineItemGroupPosition.Last,
                ),
            )
        }
    }
}
