/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.selection

import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemAttachmentsContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemEventContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemEventContentWithAttachment
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemGalleryContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextBasedContent
import io.element.android.features.messages.impl.timeline.model.event.captionOrNull
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.media.MediaSource

/**
 * How far a bulk save has got. A picture is saved as fast as it is fetched, but a video has to be
 * downloaded and decrypted first, and a dozen of them is a long silence to leave a person in.
 */
data class SelectionSaveProgress(
    val saved: Int,
    val total: Int,
)

/** One file to fetch and write to Downloads. */
data class SavableMedia(
    val source: MediaSource,
    val filename: String,
    val mimeType: String,
)

/**
 * The files an event carries, if any. An album counts as all of its pictures rather than as one
 * thing, since that is what the user sees themselves selecting.
 */
fun TimelineItemEventContent.savableMedia(): List<SavableMedia> = when (this) {
    is TimelineItemEventContentWithAttachment -> listOf(SavableMedia(mediaSource, filename, mimeType))
    is TimelineItemGalleryContent -> items.map { SavableMedia(it.mediaSource, it.filename, it.mimeType) }
    is TimelineItemAttachmentsContent -> attachments.map { SavableMedia(it.mediaSource, it.filename, it.mimeType) }
    else -> emptyList()
}

/**
 * Every file in the current selection, oldest first, or nothing at all if any part of the
 * selection is not a file.
 *
 * All or nothing on purpose: the action is offered only for a selection that can be saved whole,
 * so a mixed selection has to hide it rather than quietly save the half it can. A selected event
 * that has scrolled out of the loaded window counts as unknown, and unknown is not savable, since
 * offering to save a message we cannot even name would be a promise we might not keep.
 */
fun savableSelection(
    timelineItems: List<TimelineItem>,
    selectedIds: Set<EventId>,
): List<SavableMedia> {
    if (selectedIds.isEmpty()) return emptyList()
    val selectedEvents = timelineItems
        .allEvents()
        .filter { it.eventId != null && it.eventId in selectedIds }
        .sortedBy { it.sentTimeMillis }
        .toList()
    if (selectedEvents.mapNotNull { it.eventId }.toSet() != selectedIds) return emptyList()
    val media = selectedEvents.map { it.content.savableMedia() }
    if (media.any { it.isEmpty() }) return emptyList()
    return media.flatten()
}

/**
 * Whether the bulk save action should be shown at all. Hidden rather than disabled: a greyed out
 * button on a selection of text invites a tap that can never work, and the reason it cannot is
 * not something a disabled icon manages to say.
 */
fun canSaveSelection(
    timelineItems: List<TimelineItem>,
    selectedIds: Set<EventId>,
): Boolean = savableSelection(timelineItems, selectedIds).isNotEmpty()

/**
 * The text a selection would put on the clipboard, oldest first, or an empty string if it would
 * put nothing there.
 *
 * A picture with a caption carries text worth copying; a picture without one carries none, and
 * neither does a file. Captions count so that the action is offered exactly when it has something
 * to hand over.
 */
fun copyableSelection(
    timelineItems: List<TimelineItem>,
    selectedIds: Set<EventId>,
): String = timelineItems
    .allEvents()
    .filter { it.eventId != null && it.eventId in selectedIds }
    .sortedBy { it.sentTimeMillis }
    .mapNotNull { event ->
        (event.content as? TimelineItemTextBasedContent)?.body ?: event.content.captionOrNull()
    }
    .filter { it.isNotBlank() }
    .joinToString("\n\n")

/**
 * Whether the bulk copy action should be enabled. Disabled rather than hidden, unlike saving: copy
 * is the one thing people expect to find in a selection, and a greyed out icon in its usual place
 * says "not this selection" more clearly than an icon that comes and goes.
 */
fun canCopySelection(
    timelineItems: List<TimelineItem>,
    selectedIds: Set<EventId>,
): Boolean = copyableSelection(timelineItems, selectedIds).isNotEmpty()
