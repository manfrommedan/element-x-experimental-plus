/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.selfpush

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecentMessageIdsTest {
    @Test
    fun `first sighting is new, replays are not`() {
        val ids = RecentMessageIds()

        assertThat(ids.addIfNew("srv:m1")).isTrue()
        assertThat(ids.addIfNew("srv:m1")).isFalse()
        assertThat(ids.addIfNew("srv:m2")).isTrue()
    }

    @Test
    fun `overflow evicts the oldest ids`() {
        val ids = RecentMessageIds(capacity = 2)
        ids.addIfNew("a")
        ids.addIfNew("b")
        ids.addIfNew("c")

        assertThat(ids.addIfNew("b")).isFalse()
        // "a" fell out of the window and counts as new again.
        assertThat(ids.addIfNew("a")).isTrue()
    }
}
