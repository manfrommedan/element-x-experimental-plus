/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemDaySeparatorModel
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Surface
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.floatingDateBadgeBackground
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun BoxScope.FloatingDateBadgeOverlay(
    lazyListState: LazyListState,
    timelineItems: ImmutableList<TimelineItem>,
    isLive: Boolean,
    topOffset: Dp = 0.dp,
) {
    // This needs to be a state to trigger a `derivedState` recalculation
    val updatedTimelineItems by rememberUpdatedState(timelineItems)
    val scope = rememberCoroutineScope()
    // Cancels any in-flight tap-driven scroll so rapid taps don't queue.
    var scrollJob: Job? by remember { mutableStateOf(null) }

    // Look for the last visible item with a timestamp, starting from the last visible item and going backwards until we find one or reach the start of the list
    val lastVisibleItemWithTimestamp by remember {
        derivedStateOf {
            var index = lazyListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf null
            while (index >= 0) {
                when (val item = updatedTimelineItems.getOrNull(index)) {
                    is TimelineItem.Event -> return@derivedStateOf item
                    is TimelineItem.Virtual -> if (item.model is TimelineItemDaySeparatorModel) return@derivedStateOf item
                    is TimelineItem.GroupedEvents -> return@derivedStateOf item.events.firstOrNull()
                    null -> Unit
                }
                index--
            }
            null
        }
    }

    // Store the formatted date so we recompute it lazily and can keep it around even if we need to dispose the badge because the timeline items changed
    var formattedDate: String? by remember { mutableStateOf(null) }
    // While a tap-driven scroll is running, the badge text stays
    // anchored on the tapped date. Only a real (finger) scroll afterwards advances
    // the badge as items cross the viewport's top edge. A counter (not a flag)
    // avoids a race where the previous tap's `finally` clears the flag while the
    // next tap is still running.
    var activeTapScrolls by remember { mutableIntStateOf(0) }
    val suppressBadgeUpdates by remember { derivedStateOf { activeTapScrolls > 0 } }
    LaunchedEffect(lastVisibleItemWithTimestamp) {
        if (!suppressBadgeUpdates) {
            lastVisibleItemWithTimestamp?.formattedDate()?.let { formattedDate = it }
        }
    }

    val isAtBottom by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex < 3 && isLive
        }
    }

    var isBadgeVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        snapshotFlow { lazyListState.isScrollInProgress }
            .collectLatest { isScrolling ->
                if (isScrolling) {
                    isBadgeVisible = true
                } else {
                    delay(2000.milliseconds)
                    isBadgeVisible = false
                }
            }
    }

    val showBadge = isBadgeVisible && !isAtBottom && formattedDate != null

    AnimatedVisibility(
        visible = showBadge,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 8.dp + topOffset),
        enter = fadeIn(animationSpec = tween(150)),
        exit = fadeOut(animationSpec = tween(300)),
    ) {
        formattedDate?.let { dateText ->
            FloatingDateBadge(
                modifier = Modifier.padding(8.dp),
                dateText = dateText,
                onClick = {
                    scrollJob?.cancel()
                    scrollJob = scope.launch {
                        activeTapScrolls++
                        try {
                            // The divider for the tapped date may not be loaded yet
                            // (matrix-rust-sdk paginates lazily). Snap to the oldest loaded
                            // item so TimelinePrefetchingHelper fetches more history, then
                            // retry the lookup until the divider appears. If the oldest index
                            // stops growing across iterations the pagination has hit the room
                            // start; bail out instead of spinning. The final approach to the
                            // found divider is smoothly animated.
                            var previousOldest = -1
                            var stuckIterations = 0
                            repeat(10) {
                                val divider = findDayDividerIndex(updatedTimelineItems, dateText)
                                if (divider >= 0) {
                                    lazyListState.animateScrollToItemTop(divider)
                                    return@launch
                                }
                                val oldest = updatedTimelineItems.lastIndex
                                if (oldest <= 0) return@launch
                                if (oldest == previousOldest) {
                                    if (++stuckIterations >= 2) return@launch
                                } else {
                                    stuckIterations = 0
                                    previousOldest = oldest
                                }
                                lazyListState.scrollToItem(oldest)
                                delay(300.milliseconds)
                            }
                        } finally {
                            activeTapScrolls--
                        }
                    }
                },
            )
        }
    }
}

// matrix-rust-sdk emits one day-divider virtual item per loaded day, so the predicate matches
// at most once. Returns -1 when no divider for [formattedDate] is loaded (e.g. badge text came
// from an event at the top of the loaded window and the divider is past the pagination edge).
internal fun findDayDividerIndex(items: List<TimelineItem>, formattedDate: String): Int =
    items.indexOfFirst { item ->
        item is TimelineItem.Virtual &&
            (item.model as? TimelineItemDaySeparatorModel)?.formattedDate == formattedDate
    }

// Mirrors the codebase's animateScrollToItemCenter pattern but lands the item at the TOP of
// the viewport instead of the centre. In reverseLayout=true the layout-start is at the bottom,
// so a negative scrollOffset shifts the item up; -(viewport - item) places its top edge at
// the viewport's top edge. If the item is not currently in the viewport we snap to it first,
// then measure and animate to the precise offset (same two-phase approach as the centre helper).
private suspend fun LazyListState.animateScrollToItemTop(index: Int) {
    fun LazyListLayoutInfo.topOffsetFor(idx: Int): Int? {
        val info = visibleItemsInfo.firstOrNull { it.index == idx } ?: return null
        val containerSize = viewportSize.height - beforeContentPadding - afterContentPadding
        return -(containerSize - info.size).coerceAtLeast(0)
    }
    scroll { }
    layoutInfo.topOffsetFor(index)?.let { offset ->
        animateScrollToItem(index, offset)
        return
    }
    scrollToItem(index)
    layoutInfo.topOffsetFor(index)?.let { offset ->
        animateScrollToItem(index, offset)
    }
}

@Composable
internal fun FloatingDateBadge(
    dateText: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = if (onClick != null) {
            modifier.minimumInteractiveComponentSize().clickable(onClick = onClick)
        } else {
            modifier
        },
        shape = RoundedCornerShape(16.dp),
        color = ElementTheme.colors.floatingDateBadgeBackground,
        shadowElevation = 4.dp,
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            text = dateText,
            style = ElementTheme.typography.fontBodyMdMedium,
            color = ElementTheme.colors.textPrimary,
        )
    }
}

@PreviewsDayNight
@Composable
internal fun FloatingDateBadgePreview() = ElementPreview {
    Box(modifier = Modifier.padding(16.dp)) {
        FloatingDateBadge(dateText = "March 9, 2026")
    }
}
