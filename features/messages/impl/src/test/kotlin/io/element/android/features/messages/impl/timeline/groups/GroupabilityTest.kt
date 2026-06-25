/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.groups

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.timeline.aGroupedEvents
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemRedactedContent
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.core.UserId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test

class GroupabilityTest {
    private fun redacted(id: String, sender: String = "alice") = aTimelineItemEvent(
        eventId = EventId(id),
        senderId = UserId("@$sender:domain"),
        senderDisplayName = sender,
        content = TimelineItemRedactedContent,
    )

    private fun groupOf(vararg events: TimelineItem.Event) = TimelineItem.GroupedEvents(
        id = UniqueId("group"),
        events = events.toList().toImmutableList(),
        aggregatedReadReceipts = persistentListOf(),
    )

    @Test
    fun `a group made only of redacted events is a redacted messages group`() {
        val group = groupOf(redacted("\$R1"), redacted("\$R2"), redacted("\$R3"))
        assertThat(group.isRedactedMessagesGroup()).isTrue()
    }

    @Test
    fun `a group of state changes is not a redacted messages group`() {
        // aGroupedEvents builds a run of state events.
        assertThat(aGroupedEvents().isRedactedMessagesGroup()).isFalse()
    }

    @Test
    fun `a group mixing redacted and non-redacted events is not a redacted messages group`() {
        val group = groupOf(redacted("\$R1"), aTimelineItemEvent(eventId = EventId("\$E")), redacted("\$R2"))
        assertThat(group.isRedactedMessagesGroup()).isFalse()
    }

    @Test
    fun `an empty group is not a redacted messages group`() {
        val group = TimelineItem.GroupedEvents(
            id = UniqueId("group"),
            events = persistentListOf(),
            aggregatedReadReceipts = persistentListOf(),
        )
        assertThat(group.isRedactedMessagesGroup()).isFalse()
    }

    @Test
    fun `redacted senders summary groups by author and counts each, keeping first-seen order`() {
        val group = groupOf(
            redacted("\$R1", sender = "alice"),
            redacted("\$R2", sender = "bob"),
            redacted("\$R3", sender = "alice"),
            redacted("\$R4", sender = "alice"),
        )
        val summary = group.redactedSendersSummary()
        assertThat(summary.map { it.senderId.value }).containsExactly("@alice:domain", "@bob:domain").inOrder()
        assertThat(summary.first { it.senderId == UserId("@alice:domain") }.count).isEqualTo(3)
        assertThat(summary.first { it.senderId == UserId("@bob:domain") }.count).isEqualTo(1)
        assertThat(summary.first().senderName).isEqualTo("alice")
    }

    @Test
    fun `redacted senders summary of a single author is one entry with the full count`() {
        val group = groupOf(redacted("\$R1"), redacted("\$R2"), redacted("\$R3"))
        val summary = group.redactedSendersSummary()
        assertThat(summary).hasSize(1)
        assertThat(summary.single().count).isEqualTo(3)
    }
}
