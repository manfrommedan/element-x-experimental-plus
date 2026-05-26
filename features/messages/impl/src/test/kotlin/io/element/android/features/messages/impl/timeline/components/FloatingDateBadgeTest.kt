/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.fixtures.aMessageEvent
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemReadMarkerModel
import io.element.android.features.messages.impl.timeline.model.virtual.aTimelineItemDaySeparatorModel
import io.element.android.libraries.matrix.api.core.UniqueId
import org.junit.Test

/**
 * Unit tests for the floating date pill's divider lookup.
 *
 * The onClick logic itself (the pagination loop and the scroll animation) is
 * exercised in instrumented tests on a real LazyListState; Compose's
 * RecompositionMode.Immediate test harness collapses scroll-driven state changes
 * in ways that don't reflect real device behaviour, so we keep that flow out of
 * the unit layer.
 */
class FloatingDateBadgeTest {
    private fun daySeparator(date: String) = TimelineItem.Virtual(
        id = UniqueId("divider-$date"),
        model = aTimelineItemDaySeparatorModel(date),
    )

    private fun readMarker(id: String = "marker") = TimelineItem.Virtual(
        id = UniqueId(id),
        model = TimelineItemReadMarkerModel,
    )

    @Test
    fun `returns -1 on empty list`() {
        assertThat(findDayDividerIndex(emptyList(), "Today")).isEqualTo(-1)
    }

    @Test
    fun `returns -1 when no divider matches the requested date`() {
        val items = listOf(
            aMessageEvent(),
            daySeparator("Yesterday"),
            aMessageEvent(),
            daySeparator("Monday"),
        )
        assertThat(findDayDividerIndex(items, "Today")).isEqualTo(-1)
    }

    @Test
    fun `returns -1 when the list has no divider items at all`() {
        // Pagination edge: the badge text came from an event at the top of the
        // loaded window, but the divider for that day is past the pagination
        // boundary. The onClick fallback triggers pagination in this case.
        val items = listOf(aMessageEvent(), aMessageEvent(), aMessageEvent())
        assertThat(findDayDividerIndex(items, "Today")).isEqualTo(-1)
    }

    @Test
    fun `returns the index of the matching divider`() {
        val items = listOf(
            aMessageEvent(),
            aMessageEvent(),
            daySeparator("Today"),
            aMessageEvent(),
            daySeparator("Yesterday"),
            aMessageEvent(),
        )
        assertThat(findDayDividerIndex(items, "Today")).isEqualTo(2)
        assertThat(findDayDividerIndex(items, "Yesterday")).isEqualTo(4)
    }

    @Test
    fun `ignores non-divider virtual items`() {
        // The predicate must reject virtual items whose model is not a day
        // separator (read marker, room beginning, etc.) even if some other
        // unrelated date string is present.
        val items = listOf(
            readMarker("mark1"),
            aMessageEvent(),
            readMarker("mark2"),
            daySeparator("Today"),
        )
        assertThat(findDayDividerIndex(items, "Today")).isEqualTo(3)
    }

    @Test
    fun `matches the formatted date string exactly`() {
        val items = listOf(
            daySeparator("May 26"),
            daySeparator("May 26, 2026"),
        )
        // Strict equality - no substring/normalisation tricks.
        assertThat(findDayDividerIndex(items, "May 26")).isEqualTo(0)
        assertThat(findDayDividerIndex(items, "May 26, 2026")).isEqualTo(1)
        assertThat(findDayDividerIndex(items, "may 26")).isEqualTo(-1)
        assertThat(findDayDividerIndex(items, "26 May")).isEqualTo(-1)
    }

    @Test
    fun `returns the first matching divider when duplicates exist`() {
        // matrix-rust-sdk emits one divider per day in practice, but the
        // predicate semantics are "find the first match" - documenting the
        // contract so future readers don't expect indexOfLast behaviour.
        val items = listOf(
            daySeparator("Today"),
            aMessageEvent(),
            daySeparator("Today"),
        )
        assertThat(findDayDividerIndex(items, "Today")).isEqualTo(0)
    }
}
