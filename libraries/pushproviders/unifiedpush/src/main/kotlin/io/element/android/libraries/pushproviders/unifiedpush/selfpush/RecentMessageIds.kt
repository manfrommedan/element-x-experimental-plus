/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.selfpush

/**
 * Remembers the ids of the pushes seen most recently so a catch-up replay (the stream
 * reconnects with a `since` overlap) does not fire the notification pipeline twice for the
 * same push.
 */
class RecentMessageIds(private val capacity: Int = DEFAULT_CAPACITY) {
    private val ids = LinkedHashSet<String>()

    /** @return true the first time an id is seen, false on replays. */
    @Synchronized
    fun addIfNew(id: String): Boolean {
        if (!ids.add(id)) return false
        if (ids.size > capacity) {
            ids.remove(ids.first())
        }
        return true
    }

    private companion object {
        const val DEFAULT_CAPACITY = 256
    }
}
