/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.groups

import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemVideoContent
import io.element.android.libraries.matrix.api.core.UniqueId
import kotlinx.collections.immutable.toImmutableList
import kotlin.time.Duration.Companion.seconds

/** A run shorter than this stays as ordinary separate bubbles. */
internal const val MIN_ALBUM_SIZE = 2

/**
 * A longer run is cut into several albums of this size. A wider block makes each picture too small
 * to recognise, and every tile still has to be big enough to aim a long press at.
 */
internal const val MAX_ALBUM_SIZE = 3

/**
 * How far apart two pictures can be sent and still count as one batch.
 *
 * Telegram marks an album at send time with a shared id; Matrix has no field for that, and the one
 * standard way to say "these belong together" is a gallery event, which would make the whole batch a
 * single message and take away deleting or forwarding a single picture. So the run is inferred, and
 * the window is deliberately short: our own sender fires the items back to back, while two separate
 * batches are always split by the trip to the picker.
 */
private val ALBUM_MAX_GAP = 3.seconds

/**
 * Fold a run of consecutive pictures and videos from the same sender into a single
 * [TimelineItem.GroupedEvents], so a batch drawn from the attachment picker reads as one album
 * instead of a column of separate bubbles, the way Telegram shows it.
 *
 * Every picture stays its own event: the album is a drawing decision, not a sending one, so a single
 * item can still be deleted, replied to, forwarded and reacted to on its own, and other clients see
 * ordinary messages.
 *
 * This runs as the final step of [TimelineItemGrouper.group], next to the redacted-run collapsing.
 * Events are kept oldest-first like the other groups, and the group id goes through the grouper's
 * registry so it stays stable as history loads in around the run.
 */
internal fun List<TimelineItem>.groupMediaAlbums(groupIds: MutableMap<String, String>): List<TimelineItem> {
    val result = mutableListOf<TimelineItem>()
    val run = mutableListOf<TimelineItem.Event>()

    fun flushRun() {
        when {
            run.isEmpty() -> Unit
            run.size < MIN_ALBUM_SIZE -> result.addAll(run)
            else -> run.reversed().splitIntoAlbums().forEach { events ->
                if (events.size < MIN_ALBUM_SIZE) {
                    result.addAll(events)
                } else {
                    result.add(
                        TimelineItem.GroupedEvents(
                            id = UniqueId(groupIds.getOrPutGroupId(events)),
                            events = events.toImmutableList(),
                            aggregatedReadReceipts = events.flatMap { it.readReceiptState.receipts }.toImmutableList(),
                        )
                    )
                }
            }
        }
        run.clear()
    }

    for (item in this) {
        if (item is TimelineItem.Event && item.belongsToAnAlbumWith(run.lastOrNull())) {
            run.add(item)
        } else {
            flushRun()
            if (item is TimelineItem.Event && item.canStartAnAlbum()) {
                run.add(item)
            } else {
                result.add(item)
            }
        }
    }
    flushRun()
    return result
}

/**
 * True when every event of the group is a picture or a video, which is what tells an album apart
 * from the state-change and deleted-message groups that share [TimelineItem.GroupedEvents].
 */
internal fun TimelineItem.GroupedEvents.isMediaAlbum(): Boolean =
    events.size >= MIN_ALBUM_SIZE && events.all { it.canStartAnAlbum() }

/**
 * Cut a run into albums of at most [MAX_ALBUM_SIZE], moving one over when the tail would otherwise be
 * a single tile: four pictures read better as two and two than as three and a stray one.
 */
private fun List<TimelineItem.Event>.splitIntoAlbums(): List<List<TimelineItem.Event>> {
    val albums = chunked(MAX_ALBUM_SIZE).toMutableList()
    val last = albums.lastOrNull()
    if (albums.size > 1 && last != null && last.size == 1) {
        val previous = albums[albums.size - 2]
        albums[albums.size - 2] = previous.dropLast(1)
        albums[albums.size - 1] = listOf(previous.last()) + last
    }
    return albums
}

private fun TimelineItem.Event.canStartAnAlbum(): Boolean =
    content is TimelineItemImageContent || content is TimelineItemVideoContent

/**
 * The list is walked newest-first, so [previous] is the newer neighbour already in the run.
 */
private fun TimelineItem.Event.belongsToAnAlbumWith(previous: TimelineItem.Event?): Boolean {
    if (previous == null || !canStartAnAlbum()) return false
    if (senderId != previous.senderId) return false
    val gap = previous.sentTimeMillis - sentTimeMillis
    return gap in 0..ALBUM_MAX_GAP.inWholeMilliseconds
}
