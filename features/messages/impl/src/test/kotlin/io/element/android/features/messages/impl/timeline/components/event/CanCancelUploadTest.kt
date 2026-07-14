/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.event

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.TransactionId
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import org.junit.Test

class CanCancelUploadTest {
    private val txn = TransactionId("\$TXN")

    @Test
    fun `queued local echo (Sending Event) shows the cancel-X`() {
        // Bulk-send local echoes report Sending.Event before the queue picks them up.
        // Pre-fix only Sending.MediaWithProgress was treated as cancellable, so in a
        // 5-photo batch only the active upload got the X. This locks in coverage.
        assertThat(canCancelUpload(txn, LocalEventSendState.Sending.Event)).isTrue()
    }

    @Test
    fun `active upload (Sending MediaWithProgress) shows the cancel-X`() {
        val sending = LocalEventSendState.Sending.MediaWithProgress(index = 0L, progress = 25L, total = 100L)
        assertThat(canCancelUpload(txn, sending)).isTrue()
    }

    @Test
    fun `Sent local echo does NOT show the cancel-X`() {
        assertThat(canCancelUpload(txn, LocalEventSendState.Sent(AN_EVENT_ID))).isFalse()
    }

    @Test
    fun `null send state does NOT show the cancel-X`() {
        assertThat(canCancelUpload(txn, null)).isFalse()
    }

    @Test
    fun `null transactionId never allows cancel - we have nothing to address`() {
        assertThat(canCancelUpload(null, LocalEventSendState.Sending.Event)).isFalse()
        assertThat(canCancelUpload(null, null)).isFalse()
    }
}
