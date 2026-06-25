/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.group

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.components.avatar.Avatar
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarType
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Surface
import io.element.android.libraries.designsystem.theme.components.Text
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

private val CORNER_RADIUS = 8.dp

@Composable
fun GroupHeaderView(
    text: String,
    isExpanded: Boolean,
    @Suppress("UNUSED_PARAMETER") isHighlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingAvatars: ImmutableList<AvatarData> = persistentListOf(),
) {
    // Ignore isHighlighted for now, we need a design decision on it.
    val backgroundColor = Color.Transparent
    val shape = RoundedCornerShape(CORNER_RADIUS)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = isExpanded,
                onValueChange = { onClick() },
                role = Role.DropdownList,
            )
            .clearAndSetSemantics {
                contentDescription = text
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .clip(shape)
                .clickable(onClick = onClick),
            color = backgroundColor,
            shape = shape,
        ) {
            Row(
                modifier = Modifier
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingAvatars.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy((-4).dp),
                    ) {
                        leadingAvatars.forEach { avatarData ->
                            Avatar(
                                avatarData = avatarData,
                                avatarType = AvatarType.User,
                                forcedAvatarSize = 16.dp,
                            )
                        }
                    }
                }
                Text(
                    // Only the redacted header carries a leading avatar stack and a potentially long
                    // multi-author label. weight(fill = false) caps it at its share of the row, so a
                    // long label wraps and the chevron always keeps its place, while a short label
                    // stays its natural size and the whole header is still centred by the Box (same
                    // as the state-change "N room changes" group, which is left untouched here).
                    modifier = if (leadingAvatars.isNotEmpty()) Modifier.weight(1f, fill = false) else Modifier,
                    text = text,
                    color = ElementTheme.colors.textSecondary,
                    style = ElementTheme.typography.fontBodyMdRegular,
                )
                val rotation: Float by animateFloatAsState(
                    targetValue = if (isExpanded) 90f else 0f,
                    animationSpec = tween(
                        delayMillis = 0,
                        durationMillis = 300,
                    ),
                    label = "chevron"
                )
                Icon(
                    // The whole header is centred by the parent Box (same as the state-change
                    // "N room changes" group), so the chevron sits right after the text and a
                    // single-author label no longer hangs orphaned against the left edge.
                    modifier = Modifier.rotate(rotation),
                    imageVector = CompoundIcons.ChevronRight(),
                    contentDescription = null,
                    tint = ElementTheme.colors.iconSecondary
                )
            }
        }
    }
}

@PreviewsDayNight
@Composable
internal fun GroupHeaderViewPreview() = ElementPreview {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GroupHeaderView(
            text = "8 room changes (expanded)",
            isExpanded = true,
            isHighlighted = false,
            onClick = {}
        )
        GroupHeaderView(
            text = "8 room changes (not expanded)",
            isExpanded = false,
            isHighlighted = false,
            onClick = {}
        )
        GroupHeaderView(
            text = "8 room changes (expanded/h)",
            isExpanded = true,
            isHighlighted = true,
            onClick = {}
        )
        GroupHeaderView(
            text = "8 room changes (not expanded/h)",
            isExpanded = false,
            isHighlighted = true,
            onClick = {}
        )
    }
}
