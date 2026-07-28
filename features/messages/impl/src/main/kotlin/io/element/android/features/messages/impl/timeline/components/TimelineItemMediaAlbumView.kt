/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemVideoContent
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.ui.media.MAX_THUMBNAIL_HEIGHT
import io.element.android.libraries.matrix.ui.media.MAX_THUMBNAIL_WIDTH
import io.element.android.libraries.matrix.ui.media.MediaRequestData
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.collections.immutable.ImmutableList

/** Beyond this the album shows a "+N" tile on the last visible one. */
private const val MAX_VISIBLE_TILES = 6

private const val TILE_SPACING_DP = 2

/**
 * Draw a run of pictures and videos from one sender as a single album, the way Telegram does.
 *
 * Each tile is still its own event, so a tap opens that picture and a long press opens the menu for
 * that message alone. The album is only a layout, nothing about sending or storing changes.
 */
@Composable
fun TimelineItemMediaAlbumView(
    events: ImmutableList<TimelineItem.Event>,
    onClick: (TimelineItem.Event) -> Unit,
    onLongClick: (TimelineItem.Event) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = events.take(MAX_VISIBLE_TILES)
    val hidden = events.size - visible.size
    val columns = if (visible.size <= 4) 2 else 3
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        verticalArrangement = Arrangement.spacedBy(TILE_SPACING_DP.dp),
    ) {
        visible.chunked(columns).forEach { rowEvents ->
            Row(horizontalArrangement = Arrangement.spacedBy(TILE_SPACING_DP.dp)) {
                rowEvents.forEach { event ->
                    val isLastVisible = hidden > 0 && event === visible.last()
                    AlbumTile(
                        event = event,
                        remaining = if (isLastVisible) hidden else 0,
                        onClick = { onClick(event) },
                        onLongClick = { onLongClick(event) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keep the last row aligned with the ones above it when it is not full.
                repeat(columns - rowEvents.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AlbumTile(
    event: TimelineItem.Event,
    remaining: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(ElementTheme.colors.bgSubtlePrimary)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            modifier = Modifier.fillMaxSize(),
            model = event.albumThumbnail(),
            contentScale = ContentScale.Crop,
            contentDescription = stringResource(CommonStrings.common_image),
        )
        if (event.content is TimelineItemVideoContent) {
            Icon(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp),
                imageVector = CompoundIcons.PlaySolid(),
                contentDescription = null,
                tint = Color.White,
            )
        }
        if (remaining > 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$remaining",
                    style = ElementTheme.typography.fontHeadingSmRegular,
                    color = Color.White,
                )
            }
        }
    }
}

private fun TimelineItem.Event.albumThumbnail(): MediaRequestData? = when (val content = content) {
    is TimelineItemImageContent -> content.thumbnailMediaRequestData
    is TimelineItemVideoContent -> MediaRequestData(
        source = content.thumbnailSource,
        kind = MediaRequestData.Kind.Thumbnail(
            width = content.thumbnailWidth?.toLong() ?: MAX_THUMBNAIL_WIDTH,
            height = content.thumbnailHeight?.toLong() ?: MAX_THUMBNAIL_HEIGHT,
        ),
    )
    else -> null
}
