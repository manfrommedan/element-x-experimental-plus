/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.selection

import io.element.android.features.messages.impl.UserEventPermissions
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.libraries.matrix.api.core.EventId

/**
 * Every event of the timeline, including the ones drawn inside a group such as a media album, which
 * are not top level items and would otherwise be invisible to the selection.
 */
fun List<TimelineItem>.allEvents(): Sequence<TimelineItem.Event> = asSequence().flatMap { item ->
    when (item) {
        is TimelineItem.Event -> sequenceOf(item)
        is TimelineItem.GroupedEvents -> item.events.asSequence()
        is TimelineItem.Virtual -> emptySequence()
    }
}

/**
 * Whether the bulk Delete action should be enabled for the current selection.
 *
 * True when every selected event is loaded and the user is allowed to redact it.
 * Window-evicted selections disable the action rather than silently doing nothing.
 */
fun canDeleteSelection(
    timelineItems: List<TimelineItem>,
    selectedIds: Set<EventId>,
    userEventPermissions: UserEventPermissions,
): Boolean {
    if (selectedIds.isEmpty()) return false
    val selectedEvents = timelineItems
        .allEvents()
        .filter { it.eventId in selectedIds }
        .toList()
    return selectedEvents.size == selectedIds.size &&
        selectedEvents.none { if (it.isMine) !userEventPermissions.canRedactOwn else !userEventPermissions.canRedactOther }
}
