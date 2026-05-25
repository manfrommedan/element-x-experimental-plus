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

class DropOrphanDaySeparatorsTest {
    @Test
    fun `keeps a day separator that is followed by an event`() {
        val sep = aTimelineItemDaySeparator()
        val event = aTimelineItemEvent(eventId = EventId("\$E"))
        val result = listOf<TimelineItem>(sep, event).dropOrphanDaySeparators()
        assertThat(result).containsExactly(sep, event).inOrder()
    }

    @Test
    fun `drops a day separator at the end of the list`() {
        val event = aTimelineItemEvent(eventId = EventId("\$E"))
        val sep = aTimelineItemDaySeparator()
        val result = listOf<TimelineItem>(event, sep).dropOrphanDaySeparators()
        assertThat(result).containsExactly(event)
    }

    @Test
    fun `drops a day separator that is only followed by another day separator`() {
        // Two adjacent separators with no event between them - the first is orphan.
        val sep1 = aTimelineItemDaySeparator()
        val sep2 = aTimelineItemDaySeparator()
        val event = aTimelineItemEvent(eventId = EventId("\$E"))
        val result = listOf<TimelineItem>(sep1, sep2, event).dropOrphanDaySeparators()
        // sep1 is orphan (next non-separator is sep2 which is itself a separator;
        // the lookahead stops at the next separator and finds no event between them).
        // sep2 is kept because event follows it.
        assertThat(result).containsExactly(sep2, event).inOrder()
    }

    @Test
    fun `keeps every separator when each is followed by an event before the next separator`() {
        val sep1 = aTimelineItemDaySeparator()
        val e1 = aTimelineItemEvent(eventId = EventId("\$E1"))
        val sep2 = aTimelineItemDaySeparator()
        val e2 = aTimelineItemEvent(eventId = EventId("\$E2"))
        val result = listOf<TimelineItem>(sep1, e1, sep2, e2).dropOrphanDaySeparators()
        assertThat(result).containsExactly(sep1, e1, sep2, e2).inOrder()
    }

    @Test
    fun `is a no-op on an empty list`() {
        val result = emptyList<TimelineItem>().dropOrphanDaySeparators()
        assertThat(result).isEmpty()
    }
}
