/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.event

import android.text.SpannedString
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.components.ATimelineItemEventRow
import io.element.android.features.messages.impl.timeline.components.layout.ContentAvoidingLayout
import io.element.android.features.messages.impl.timeline.components.layout.ContentAvoidingLayoutData
import io.element.android.features.messages.impl.timeline.model.TimelineItemGroupPosition
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemImageContentProvider
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemImageContent
import io.element.android.features.messages.impl.timeline.protection.ProtectedView
import io.element.android.features.messages.impl.timeline.protection.coerceRatioWhenHidingContent
import io.element.android.libraries.designsystem.components.blurhash.blurHashBackground
import io.element.android.libraries.designsystem.components.media.LocalAutoLoadMedia
import io.element.android.libraries.designsystem.modifiers.onKeyboardContextMenuAction
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.textcomposer.ElementRichTextEditorStyle
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.libraries.ui.utils.a11y.isTalkbackActive
import io.element.android.wysiwyg.compose.EditorStyledText
import io.element.android.wysiwyg.link.Link

private const val TALL_IMAGE_RATIO_DIVISOR = 3
@Composable
fun TimelineItemImageView(
    content: TimelineItemImageContent,
    hideMediaContent: Boolean,
    onContentClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    onLinkClick: (Link) -> Unit,
    onLinkLongClick: (Link) -> Unit,
    onShowContentClick: () -> Unit,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit,
    modifier: Modifier = Modifier,
    uploadProgress: io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState.Sending.MediaWithProgress? = null,
    onCancelUpload: (() -> Unit)? = null,
) {
    val a11yLabel = stringResource(CommonStrings.common_image)
    val description = content.caption?.let { "$a11yLabel: $it" } ?: a11yLabel
    Column(modifier = modifier) {
        val containerModifier = if (content.showCaption) {
            Modifier.clip(RoundedCornerShape(10.dp))
        } else {
            Modifier
        }
        TimelineItemAspectRatioBox(
            modifier = containerModifier.blurHashBackground(content.blurhash, alpha = 0.9f).align(Alignment.CenterHorizontally),
            aspectRatio = coerceRatioWhenHidingContent(content.aspectRatio, hideMediaContent),
        ) {
            ProtectedView(
                hideContent = hideMediaContent,
                onShowClick = onShowContentClick,
            ) {
                val autoLoad = LocalAutoLoadMedia.current
                var userTapped by rememberSaveable { mutableStateOf(false) }
                val networkAllowed = autoLoad || userTapped || onCancelUpload != null
                val context = LocalPlatformContext.current
                // Always issue the request, but disable the network leg when wifi-only
                // is on and the user hasn't asked. Memory + disk cache still satisfy it,
                // so a thumbnail Coil already has stays visible across scrolls instead of
                // reverting to the tap-to-download placeholder.
                val request = remember(content.thumbnailMediaRequestData, networkAllowed) {
                    ImageRequest.Builder(context)
                        .data(content.thumbnailMediaRequestData)
                        .apply {
                            if (!networkAllowed) networkCachePolicy(CachePolicy.DISABLED)
                        }
                        .build()
                }
                val painter = rememberAsyncImagePainter(model = request)
                val painterState by painter.state.collectAsState()
                val isLoaded = painterState is AsyncImagePainter.State.Success
                val showTapToDownload = shouldShowTapToDownload(networkAllowed, painterState)
                if (!showTapToDownload) {
                    androidx.compose.foundation.Image(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (onCancelUpload != null) Modifier.blur(12.dp) else Modifier)
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
                        painter = painter,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.Center,
                        contentDescription = description,
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
                        progress = uploadProgress?.progress ?: 0L,
                        total = uploadProgress?.total ?: 0L,
                        onCancel = onCancelUpload,
                    )
                }
            }
        }

        if (content.showCaption) {
            Spacer(modifier = Modifier.height(8.dp))
            val caption = if (LocalInspectionMode.current) {
                SpannedString(content.caption)
            } else {
                content.formattedCaption ?: SpannedString(content.caption)
            }
            CompositionLocalProvider(
                LocalContentColor provides ElementTheme.colors.textPrimary,
                LocalTextStyle provides ElementTheme.typography.fontBodyLgRegular
            ) {
                val width = content.width ?: 0
                val height = content.height ?: 0
                // if image is narrow and tall use DEFAULT_ASPECT_RATIO
                val aspectRatio = if (width < height / TALL_IMAGE_RATIO_DIVISOR) {
                    DEFAULT_ASPECT_RATIO
                } else {
                    content.aspectRatio ?: DEFAULT_ASPECT_RATIO
                }
                EditorStyledText(
                    modifier = Modifier
                        .padding(horizontal = 4.dp) // This is (12.dp - 8.dp) contentPadding from CommonLayout
                        .widthIn(min = MIN_HEIGHT_IN_DP.dp * aspectRatio, max = MAX_HEIGHT_IN_DP.dp * aspectRatio),
                    text = caption,
                    style = ElementRichTextEditorStyle.textStyle(),
                    onLinkClickedListener = onLinkClick,
                    onLinkLongClickedListener = onLinkLongClick,
                    releaseOnDetach = false,
                    onTextLayout = ContentAvoidingLayout.measureLegacyLastTextLine(onContentLayoutChange = onContentLayoutChange),
                )
            }
        }
    }
}

@PreviewsDayNight
@Composable
internal fun TimelineItemImageViewPreview(@PreviewParameter(TimelineItemImageContentProvider::class) content: TimelineItemImageContent) = ElementPreview {
    TimelineItemImageView(
        content = content,
        hideMediaContent = false,
        onShowContentClick = {},
        onContentClick = {},
        onLongClick = {},
        onLinkClick = {},
        onLinkLongClick = {},
        onContentLayoutChange = {},
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
        onLinkClick = {},
        onLinkLongClick = {},
        onContentLayoutChange = {},
    )
}

@PreviewsDayNight
@Composable
internal fun TimelineImageWithCaptionRowPreview() = ElementPreview {
    Column {
        sequenceOf(false, true).forEach { isMine ->
            ATimelineItemEventRow(
                event = aTimelineItemEvent(
                    isMine = isMine,
                    content = aTimelineItemImageContent(
                        filename = "image.jpg",
                        caption = "A long caption that may wrap into several lines",
                        aspectRatio = 2.5f,
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
                    caption = "Image with null aspectRatio",
                    aspectRatio = null,
                ),
                groupPosition = TimelineItemGroupPosition.Last,
            ),
        )
    }
}

@PreviewsDayNight
@Composable
internal fun ATimelineItemEventRowPreview() = ElementPreview {
    Column {
        sequenceOf(false, true).forEach { isMine ->
            ATimelineItemEventRow(
                event = aTimelineItemEvent(
                    isMine = isMine,
                    content = aTimelineItemImageContent(
                        filename = "image.jpg",
                        caption = "A long caption that may wrap into several lines",
                        width = 80,
                        height = 300,
                        aspectRatio = 80f / 300f,
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
                    height = 300,
                    aspectRatio = null,
                ),
                groupPosition = TimelineItemGroupPosition.Last,
            ),
        )
    }
}
