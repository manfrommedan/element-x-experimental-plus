/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.selection

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.components.event.aGalleryItem
import io.element.android.features.messages.impl.timeline.components.event.aTimelineItemGalleryContent
import io.element.android.features.messages.impl.timeline.model.event.GalleryItem
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemFileContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemTextContent
import io.element.android.libraries.matrix.api.core.EventId
import org.junit.Test

class SavableSelectionTest {
    @Test
    fun `empty selection has nothing to save`() {
        val items = listOf(aTimelineItemEvent(eventId = EventId("\$1"), content = aTimelineItemImageContent()))
        assertThat(canSaveSelection(items, emptySet())).isFalse()
    }

    @Test
    fun `a selection of files can be saved`() {
        val items = listOf(
            aTimelineItemEvent(eventId = EventId("\$1"), content = aTimelineItemImageContent(filename = "photo.jpg")),
            aTimelineItemEvent(eventId = EventId("\$2"), content = aTimelineItemFileContent(fileName = "notes.pdf")),
        )
        val saved = savableSelection(items, setOf(EventId("\$1"), EventId("\$2")))
        assertThat(saved.map { it.filename }).containsExactly("photo.jpg", "notes.pdf")
    }

    @Test
    fun `one text message in the selection takes the action away`() {
        val items = listOf(
            aTimelineItemEvent(eventId = EventId("\$1"), content = aTimelineItemImageContent()),
            aTimelineItemEvent(eventId = EventId("\$2"), content = aTimelineItemTextContent()),
        )
        assertThat(savableSelection(items, setOf(EventId("\$1"), EventId("\$2")))).isEmpty()
        assertThat(canSaveSelection(items, setOf(EventId("\$1"), EventId("\$2")))).isFalse()
    }

    @Test
    fun `a selected event that has scrolled out of the window counts as unknown`() {
        // We cannot say what $2 holds, so we do not offer to save a selection containing it.
        val items = listOf(aTimelineItemEvent(eventId = EventId("\$1"), content = aTimelineItemImageContent()))
        assertThat(canSaveSelection(items, setOf(EventId("\$1"), EventId("\$2")))).isFalse()
    }

    @Test
    fun `pictures without captions have nothing to copy`() {
        val items = listOf(
            aTimelineItemEvent(eventId = EventId("\$1"), content = aTimelineItemImageContent()),
            aTimelineItemEvent(eventId = EventId("\$2"), content = aTimelineItemImageContent()),
        )
        assertThat(canCopySelection(items, setOf(EventId("\$1"), EventId("\$2")))).isFalse()
    }

    @Test
    fun `a caption is text, and text is worth copying`() {
        val items = listOf(
            aTimelineItemEvent(eventId = EventId("\$1"), content = aTimelineItemImageContent(caption = "on the beach")).copy(sentTimeMillis = 1000L),
            aTimelineItemEvent(eventId = EventId("\$2"), content = aTimelineItemImageContent()).copy(sentTimeMillis = 2000L),
        )
        assertThat(canCopySelection(items, setOf(EventId("\$1"), EventId("\$2")))).isTrue()
        // The captionless picture contributes nothing rather than a blank line.
        assertThat(copyableSelection(items, setOf(EventId("\$1"), EventId("\$2")))).isEqualTo("on the beach")
    }

    @Test
    fun `text and captions are copied together, oldest first`() {
        val items = listOf(
            aTimelineItemEvent(eventId = EventId("\$1"), content = aTimelineItemTextContent(body = "look")).copy(sentTimeMillis = 1000L),
            aTimelineItemEvent(eventId = EventId("\$2"), content = aTimelineItemImageContent(caption = "at this")).copy(sentTimeMillis = 2000L),
        )
        assertThat(copyableSelection(items, setOf(EventId("\$1"), EventId("\$2")))).isEqualTo("look\n\nat this")
    }

    @Test
    fun `an album counts as all of its pictures`() {
        val gallery = aTimelineItemGalleryContent(
            items = listOf(
                aGalleryItem(filename = "one.jpg"),
                aGalleryItem(filename = "two.jpg"),
                aGalleryItem(filename = "three.mp4", type = GalleryItem.Type.Video),
            ),
        )
        val items = listOf(aTimelineItemEvent(eventId = EventId("\$1"), content = gallery))
        val saved = savableSelection(items, setOf(EventId("\$1")))
        assertThat(saved.map { it.filename }).containsExactly("one.jpg", "two.jpg", "three.mp4")
    }
}
