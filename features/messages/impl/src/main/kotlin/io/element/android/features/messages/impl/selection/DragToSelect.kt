/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.selection

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.isBulkSelectable
import io.element.android.libraries.matrix.api.core.EventId
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Each timeline message row reports its own on-screen bounds here, like a DOM element. The drag
 * gesture then hit-tests the finger against those bounds instead of doing coordinate math against
 * LazyColumn layout offsets - bounds are in window space, so there is nothing for reverseLayout,
 * contentPadding or variable row heights to drift.
 */
class DragSelectRegistry {
    private val bounds = mutableStateMapOf<EventId, Rect>()

    fun put(id: EventId, rect: Rect) {
        bounds[id] = rect
    }

    fun remove(id: EventId) {
        bounds.remove(id)
    }

    /**
     * Event whose row contains [windowPoint] (window coordinates), or null. Hit-tests on the Y
     * axis only (the list is vertical) and, when rows momentarily overlap during scroll or the
     * checkbox enter-animation, picks the one whose centre is closest - so the result never
     * depends on the map's (unspecified) iteration order.
     */
    fun eventAt(windowPoint: Offset): EventId? {
        if (bounds.isEmpty()) return null
        val y = windowPoint.y
        // Prefer the row whose vertical span contains the point; if the finger sits in an
        // inter-row gap or in the list's content padding (e.g. held at an edge while
        // auto-scrolling), fall back to the row whose centre is nearest so the range keeps
        // growing instead of stalling. Nearest-centre also disambiguates momentary overlaps.
        val containing = bounds.entries.filter { y >= it.value.top && y < it.value.bottom }
        val pool = if (containing.isNotEmpty()) containing else bounds.entries
        return pool.minByOrNull { kotlin.math.abs((it.value.top + it.value.bottom) / 2f - y) }?.key
    }
}

val LocalDragSelectRegistry = compositionLocalOf<DragSelectRegistry?> { null }

/**
 * Telegram-style drag-to-select. Anchor = the exact event the long-press fired on (pushed into
 * [anchorState] by the per-row handler; cleared on every touch-down so a press in the gutter,
 * which sets no anchor, is ignored). The moving finger is resolved through the per-row bounds
 * registry, not layout-offset math. Holding near an edge auto-scrolls while the range grows.
 */
@Suppress("ModifierComposable", "ComposeModifierComposed")
fun Modifier.dragToSelectMessages(
    lazyListState: LazyListState,
    items: List<TimelineItem>,
    currentSelection: ImmutableSet<EventId>?,
    enabled: Boolean,
    maxSelection: Int,
    reverseLayout: Boolean,
    anchorState: MutableState<EventId?>,
    onSelectionChange: (ImmutableSet<EventId>) -> Unit,
): Modifier = composed {
    val registry = LocalDragSelectRegistry.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val view = LocalView.current
    val latestItems by rememberUpdatedState(items)
    val latestBase by rememberUpdatedState(currentSelection ?: persistentSetOf())
    val latestOnChange by rememberUpdatedState(onSelectionChange)
    var listCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val edgePx = with(density) { 72.dp.toPx() }
    val stepPx = with(density) { 14.dp.toPx() }

    this
        .onGloballyPositioned { listCoords = it }
        // Clear any stale anchor at the very start of every touch, before the long-press timeout.
        .then(
            if (enabled) {
                Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        anchorState.value = null
                    }
                }
            } else {
                Modifier
            }
        )
        .pointerInput(enabled, registry) {
            if (!enabled || registry == null) return@pointerInput
            var anchorIndex = -1
            var targetIndex = -1
            var baseSelection: ImmutableSet<EventId> = persistentSetOf()
            var pointerLocal = Offset.Zero
            var autoScroll: Job? = null

            fun indexOfEvent(id: EventId?): Int =
                if (id == null) -1 else latestItems.indexOfFirst { (it as? TimelineItem.Event)?.eventId == id }

            // Window-space hit-test against per-row bounds.
            fun targetIndexAt(local: Offset): Int? {
                val coords = listCoords ?: return null
                val id = registry.eventAt(coords.localToWindow(local)) ?: return null
                return indexOfEvent(id).takeIf { it >= 0 }
            }

            fun emitRange() {
                if (anchorIndex < 0 || targetIndex < 0) return
                val lo = minOf(anchorIndex, targetIndex)
                val hi = maxOf(anchorIndex, targetIndex)
                val range = (lo..hi).asSequence()
                    .mapNotNull { latestItems.getOrNull(it) as? TimelineItem.Event }
                    .filter { it.content.isBulkSelectable() }
                    .mapNotNull { it.eventId }
                // Range FIRST: the actively swept run (which always includes the anchor) must
                // never be truncated by the cap. Pre-existing taps fill the remaining budget.
                val combined = (range + baseSelection.asSequence()).distinct().toList()
                val capped = if (combined.size > maxSelection) combined.take(maxSelection) else combined
                latestOnChange(capped.toPersistentSet())
            }

            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    val anchorId = anchorState.value
                    anchorState.value = null // one-shot consume
                    val idx = indexOfEvent(anchorId)
                    if (anchorId != null && idx >= 0) {
                        anchorIndex = idx
                        targetIndex = idx
                        baseSelection = latestBase
                        pointerLocal = offset
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        emitRange()
                        autoScroll = scope.launch {
                            while (isActive) {
                                val h = size.height.toFloat()
                                val y = pointerLocal.y
                                val raw = when {
                                    y < edgePx -> -stepPx * (1f - (y / edgePx)).coerceIn(0f, 1f)
                                    y > h - edgePx -> stepPx * (1f - ((h - y) / edgePx)).coerceIn(0f, 1f)
                                    else -> 0f
                                }
                                val delta = if (reverseLayout) -raw else raw
                                if (delta != 0f) {
                                    lazyListState.scrollBy(delta)
                                    targetIndexAt(pointerLocal)?.let {
                                        if (it != targetIndex) {
                                            targetIndex = it
                                            emitRange()
                                        }
                                    }
                                }
                                delay(16)
                            }
                        }
                    } else {
                        // Gutter / stale anchor -> do not start a selection.
                        anchorIndex = -1
                        targetIndex = -1
                    }
                },
                onDrag = { change, _ ->
                    if (anchorIndex >= 0) {
                        pointerLocal = change.position
                        change.consume()
                        targetIndexAt(pointerLocal)?.let {
                            if (it != targetIndex) {
                                targetIndex = it
                                emitRange()
                            }
                        }
                    }
                },
                onDragEnd = {
                    autoScroll?.cancel()
                    autoScroll = null
                    anchorIndex = -1
                    targetIndex = -1
                },
                onDragCancel = {
                    autoScroll?.cancel()
                    autoScroll = null
                    anchorIndex = -1
                    targetIndex = -1
                },
            )
        }
}
