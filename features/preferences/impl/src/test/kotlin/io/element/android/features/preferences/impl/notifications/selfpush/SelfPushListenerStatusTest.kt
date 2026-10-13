/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.notifications.selfpush

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushRegistration
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushStreamStatus
import org.junit.Test

class SelfPushListenerStatusTest {
    private val registrations = mapOf(
        "secret1" to SelfPushRegistration("https://ntfy.sh", "upA"),
    )

    @Test
    fun `inactive distributor or no registrations report INACTIVE`() {
        assertThat(status(active = false, registrations = registrations)).isEqualTo(SelfPushSettingsState.ListenerStatus.INACTIVE)
        assertThat(status(active = true, registrations = emptyMap())).isEqualTo(SelfPushSettingsState.ListenerStatus.INACTIVE)
    }

    @Test
    fun `server without a live state falls back to the persisted open flags`() {
        assertThat(
            status(active = true, registrations = registrations, openServers = setOf("https://ntfy.sh"))
        ).isEqualTo(SelfPushSettingsState.ListenerStatus.CONNECTED)
        assertThat(
            status(active = true, registrations = registrations, openServers = emptySet())
        ).isEqualTo(SelfPushSettingsState.ListenerStatus.CONNECTING)
    }

    @Test
    fun `live states win over the persisted flags`() {
        assertThat(
            status(
                active = true,
                registrations = registrations,
                openServers = setOf("https://ntfy.sh"),
                liveStatuses = mapOf("https://ntfy.sh" to SelfPushStreamStatus.RECONNECTING),
            )
        ).isEqualTo(SelfPushSettingsState.ListenerStatus.RECONNECTING)
        assertThat(
            status(
                active = true,
                registrations = registrations,
                openServers = setOf("https://ntfy.sh"),
                liveStatuses = mapOf("https://ntfy.sh" to SelfPushStreamStatus.REFUSED),
            )
        ).isEqualTo(SelfPushSettingsState.ListenerStatus.REFUSED)
    }

    @Test
    fun `no network is reported only when every stream is parked`() {
        val twoServers = registrations + ("secret2" to SelfPushRegistration("https://push.example.com", "upB"))
        assertThat(
            status(
                active = true,
                registrations = twoServers,
                liveStatuses = mapOf(
                    "https://ntfy.sh" to SelfPushStreamStatus.NO_NETWORK,
                    "https://push.example.com" to SelfPushStreamStatus.NO_NETWORK,
                ),
            )
        ).isEqualTo(SelfPushSettingsState.ListenerStatus.NO_NETWORK)
        assertThat(
            status(
                active = true,
                registrations = twoServers,
                liveStatuses = mapOf(
                    "https://ntfy.sh" to SelfPushStreamStatus.NO_NETWORK,
                    "https://push.example.com" to SelfPushStreamStatus.CONNECTED,
                ),
            )
        ).isEqualTo(SelfPushSettingsState.ListenerStatus.CONNECTED)
    }

    private fun status(
        active: Boolean,
        registrations: Map<String, SelfPushRegistration>,
        openServers: Set<String> = emptySet(),
        liveStatuses: Map<String, SelfPushStreamStatus> = emptyMap(),
    ) = selfPushListenerStatus(
        active = active,
        registrations = registrations,
        openServers = openServers,
        liveStatuses = liveStatuses,
    )
}
