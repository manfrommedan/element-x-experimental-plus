/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.rtc

import com.google.common.truth.Truth.assertThat
import io.element.android.tests.testutils.testCoroutineDispatchers
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.DelayedEventUpdateAction
import org.matrix.rustcomponents.sdk.Room

class SdkMatrixBackendTest {
    private val client = mockk<Client>()
    private val room = mockk<Room>()

    @Before
    fun setUp() {
        every { client.userId() } returns USER_ID
        every { client.deviceId() } returns DEVICE_ID
        every { client.getRoom(ROOM_ID) } returns room
        every { room.close() } just runs
    }

    @Test
    fun `a delayed state event is sent through the SDK and returns its delay id`() = runTest {
        coEvery {
            room.sendDelayedStateEvent(EVENT_TYPE, STATE_KEY, CONTENT, DELAY_MS, any())
        } returns DELAY_ID

        val result = createBackend().sendDelayedStateEvent(ROOM_ID, EVENT_TYPE, STATE_KEY, CONTENT, DELAY_MS)

        assertThat(result).isEqualTo(DELAY_ID)
        coVerify(exactly = 1) { room.sendDelayedStateEvent(EVENT_TYPE, STATE_KEY, CONTENT, DELAY_MS, any()) }
    }

    @Test
    fun `a delayed sticky event keeps delay and sticky duration in the SDK argument order`() = runTest {
        coEvery {
            room.sendStickyDelayedEvent(EVENT_TYPE, CONTENT, DELAY_MS, STICKY_DURATION_MS, any())
        } returns DELAY_ID

        val result = createBackend().sendDelayedStickyEvent(ROOM_ID, EVENT_TYPE, CONTENT, STICKY_DURATION_MS, DELAY_MS)

        assertThat(result).isEqualTo(DELAY_ID)
        coVerify(exactly = 1) { room.sendStickyDelayedEvent(EVENT_TYPE, CONTENT, DELAY_MS, STICKY_DURATION_MS, any()) }
    }

    @Test
    fun `restarting a delayed event refreshes the dead man's switch`() = runTest {
        coEvery { client.updateDelayedEvent(DELAY_ID, DelayedEventUpdateAction.RESTART, any()) } returns Unit

        createBackend().restartDelayedEvent(ROOM_ID, DELAY_ID)

        coVerify(exactly = 1) { client.updateDelayedEvent(DELAY_ID, DelayedEventUpdateAction.RESTART, any()) }
    }

    @Test
    fun `cancelling a delayed event lets a deliberate hang up take effect immediately`() = runTest {
        coEvery { client.updateDelayedEvent(DELAY_ID, DelayedEventUpdateAction.CANCEL, any()) } returns Unit

        createBackend().cancelDelayedEvent(ROOM_ID, DELAY_ID)

        coVerify(exactly = 1) { client.updateDelayedEvent(DELAY_ID, DelayedEventUpdateAction.CANCEL, any()) }
    }

    private fun TestScope.createBackend() = SdkMatrixBackend(
        client = client,
        sessionScope = backgroundScope,
        dispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true),
    )

    private companion object {
        const val ROOM_ID = "!room:example.org"
        const val USER_ID = "@alice:example.org"
        const val DEVICE_ID = "DEVICE"
        const val EVENT_TYPE = "m.call.member"
        const val STATE_KEY = "@alice:example.org_DEVICE"
        const val CONTENT = """{"call_id":""}"""
        const val DELAY_ID = "delay-id"
        val DELAY_MS = 5_000uL
        val STICKY_DURATION_MS = 30_000uL
    }
}
