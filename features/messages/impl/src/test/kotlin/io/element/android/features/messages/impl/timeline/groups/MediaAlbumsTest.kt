/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.groups

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemTextContent
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UserId
import org.junit.Test

class MediaAlbumsTest {
    // The timeline is walked newest first, so a lower index means a more recent message.
    private fun picture(id: String, sender: String = "alice", sentTimeMillis: Long = 0L) = aTimelineItemEvent(
        eventId = EventId(id),
        senderId = UserId("@$sender:domain"),
        senderDisplayName = sender,
        content = aTimelineItemImageContent(),
    ).copy(sentTimeMillis = sentTimeMillis)

    private fun text(id: String, sentTimeMillis: Long = 0L) = aTimelineItemEvent(
        eventId = EventId(id),
        content = aTimelineItemTextContent(),
    ).copy(sentTimeMillis = sentTimeMillis)

    @Test
    fun `a run of pictures from one sender becomes a single album`() {
        val items = listOf(
            picture("\$p3", sentTimeMillis = 3_000),
            picture("\$p2", sentTimeMillis = 2_000),
            picture("\$p1", sentTimeMillis = 1_000),
        )

        val result = items.groupMediaAlbums(mutableMapOf())

        val album = result.single() as TimelineItem.GroupedEvents
        assertThat(album.isMediaAlbum()).isTrue()
        // Oldest first, like the other groups.
        assertThat(album.events.map { it.eventId }).containsExactly(
            EventId("\$p1"),
            EventId("\$p2"),
            EventId("\$p3"),
        ).inOrder()
    }

    @Test
    fun `a single picture is left on its own`() {
        val items = listOf(picture("\$p1"), text("\$t1"))

        val result = items.groupMediaAlbums(mutableMapOf())

        assertThat(result).hasSize(2)
        assertThat(result.none { it is TimelineItem.GroupedEvents }).isTrue()
    }

    @Test
    fun `a message in between splits the run in two albums`() {
        val items = listOf(
            picture("\$p4", sentTimeMillis = 4_000),
            picture("\$p3", sentTimeMillis = 3_000),
            text("\$t1", sentTimeMillis = 2_500),
            picture("\$p2", sentTimeMillis = 2_000),
            picture("\$p1", sentTimeMillis = 1_000),
        )

        val result = items.groupMediaAlbums(mutableMapOf())

        assertThat(result).hasSize(3)
        assertThat(result.filterIsInstance<TimelineItem.GroupedEvents>()).hasSize(2)
    }

    @Test
    fun `pictures from different senders do not share an album`() {
        val items = listOf(
            picture("\$p2", sender = "bob", sentTimeMillis = 2_000),
            picture("\$p1", sender = "alice", sentTimeMillis = 1_000),
        )

        val result = items.groupMediaAlbums(mutableMapOf())

        assertThat(result).hasSize(2)
        assertThat(result.none { it is TimelineItem.GroupedEvents }).isTrue()
    }

    @Test
    fun `pictures sent far apart do not share an album`() {
        val items = listOf(
            picture("\$p2", sentTimeMillis = 10.minutesInMillis()),
            picture("\$p1", sentTimeMillis = 0),
        )

        val result = items.groupMediaAlbums(mutableMapOf())

        assertThat(result).hasSize(2)
        assertThat(result.none { it is TimelineItem.GroupedEvents }).isTrue()
    }

    private fun Int.minutesInMillis() = this * 60_000L
}
