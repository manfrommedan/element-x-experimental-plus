/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.libraries.matrix.api.core.EventId
import org.junit.Test

// The timeline list is newest-first: a day's events come before (lower index than) their day
// separator, which sits above them. So a non-orphan layout is [event, separator]; an orphan
// separator is one with no event of its own day immediately preceding it.
class DropOrphanDaySeparatorsTest {
    @Test
    fun `keeps a day separator that is preceded by an event`() {
        val event = aTimelineItemEvent(eventId = EventId("\$E"))
        val sep = aTimelineItemDaySeparator()
        val result = listOf<TimelineItem>(event, sep).dropOrphanDaySeparators()
        assertThat(result).containsExactly(event, sep).inOrder()
    }

    @Test
    fun `drops a day separator at the start of the list`() {
        // The separator is the newest item with no event preceding it - it has no day to label.
        val sep = aTimelineItemDaySeparator()
        val event = aTimelineItemEvent(eventId = EventId("\$E"))
        val result = listOf<TimelineItem>(sep, event).dropOrphanDaySeparators()
        assertThat(result).containsExactly(event)
    }

    @Test
    fun `drops a day separator that is only preceded by another day separator`() {
        // Two adjacent separators with no event between them - the later one is orphan.
        val event = aTimelineItemEvent(eventId = EventId("\$E"))
        val sep1 = aTimelineItemDaySeparator()
        val sep2 = aTimelineItemDaySeparator()
        val result = listOf<TimelineItem>(event, sep1, sep2).dropOrphanDaySeparators()
        // sep1 is kept because event precedes it; sep2 is orphan (the scan towards lower indices
        // hits sep1, a separator, before finding any event).
        assertThat(result).containsExactly(event, sep1).inOrder()
    }

    @Test
    fun `keeps every separator when each has an event before it after the previous separator`() {
        val e1 = aTimelineItemEvent(eventId = EventId("\$E1"))
        val sep1 = aTimelineItemDaySeparator()
        val e2 = aTimelineItemEvent(eventId = EventId("\$E2"))
        val sep2 = aTimelineItemDaySeparator()
        val result = listOf<TimelineItem>(e1, sep1, e2, sep2).dropOrphanDaySeparators()
        assertThat(result).containsExactly(e1, sep1, e2, sep2).inOrder()
    }

    @Test
    fun `keeps the oldest day separator when its day has events`() {
        // Regression: the oldest day's separator is the last item in the list; its events precede
        // it. The previous (buggy) forward scan always dropped it because nothing follows it.
        val newEvent = aTimelineItemEvent(eventId = EventId("\$NEW"))
        val newSep = aTimelineItemDaySeparator()
        val oldEvent = aTimelineItemEvent(eventId = EventId("\$OLD"))
        val oldSep = aTimelineItemDaySeparator()
        val result = listOf<TimelineItem>(newEvent, newSep, oldEvent, oldSep).dropOrphanDaySeparators()
        assertThat(result).containsExactly(newEvent, newSep, oldEvent, oldSep).inOrder()
    }

    @Test
    fun `is a no-op on an empty list`() {
        val result = emptyList<TimelineItem>().dropOrphanDaySeparators()
        assertThat(result).isEmpty()
    }
}
