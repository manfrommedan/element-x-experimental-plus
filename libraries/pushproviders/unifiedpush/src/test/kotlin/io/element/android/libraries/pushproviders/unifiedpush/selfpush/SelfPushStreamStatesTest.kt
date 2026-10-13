/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.selfpush

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SelfPushStreamStatesTest {
    @Test
    fun `set and remove update the live states`() {
        val states = SelfPushStreamStates()
        assertThat(states.states.value).isEmpty()

        states.set("https://ntfy.sh", SelfPushStreamStatus.CONNECTING)
        states.set("https://push.example.com", SelfPushStreamStatus.CONNECTED)
        assertThat(states.states.value).containsExactly(
            "https://ntfy.sh",
            SelfPushStreamStatus.CONNECTING,
            "https://push.example.com",
            SelfPushStreamStatus.CONNECTED,
        )

        states.set("https://ntfy.sh", SelfPushStreamStatus.CONNECTED)
        assertThat(states.states.value["https://ntfy.sh"]).isEqualTo(SelfPushStreamStatus.CONNECTED)

        states.remove("https://ntfy.sh")
        assertThat(states.states.value).containsExactly("https://push.example.com", SelfPushStreamStatus.CONNECTED)
    }

    @Test
    fun `worst status is null for an empty set`() {
        assertThat(emptyList<SelfPushStreamStatus>().worstSelfPushStatus()).isNull()
    }

    @Test
    fun `worst status prefers the state needing attention`() {
        assertThat(
            listOf(SelfPushStreamStatus.CONNECTED, SelfPushStreamStatus.REFUSED).worstSelfPushStatus()
        ).isEqualTo(SelfPushStreamStatus.REFUSED)
        assertThat(
            listOf(SelfPushStreamStatus.CONNECTED, SelfPushStreamStatus.RECONNECTING).worstSelfPushStatus()
        ).isEqualTo(SelfPushStreamStatus.RECONNECTING)
        assertThat(
            listOf(SelfPushStreamStatus.CONNECTED, SelfPushStreamStatus.CONNECTING).worstSelfPushStatus()
        ).isEqualTo(SelfPushStreamStatus.CONNECTING)
        assertThat(
            listOf(SelfPushStreamStatus.CONNECTED).worstSelfPushStatus()
        ).isEqualTo(SelfPushStreamStatus.CONNECTED)
    }

    @Test
    fun `worst status reports no network only when every stream is parked`() {
        assertThat(
            listOf(SelfPushStreamStatus.NO_NETWORK, SelfPushStreamStatus.NO_NETWORK).worstSelfPushStatus()
        ).isEqualTo(SelfPushStreamStatus.NO_NETWORK)
        assertThat(
            listOf(SelfPushStreamStatus.NO_NETWORK, SelfPushStreamStatus.CONNECTED).worstSelfPushStatus()
        ).isEqualTo(SelfPushStreamStatus.CONNECTED)
    }
}
