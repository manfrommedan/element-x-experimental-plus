/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.utils

import com.google.common.truth.Truth.assertThat
import io.element.android.features.call.api.CallSummary
import io.element.android.features.call.impl.utils.CallSummaryCodec
import org.junit.Test

class CallSummaryCodecTest {
    @Test
    fun `NoAnswer round-trips`() {
        val encoded = CallSummaryCodec.encode(CallSummary.NoAnswer)
        assertThat(encoded).isEqualTo("noanswer")
        assertThat(CallSummaryCodec.decode(encoded)).isEqualTo(CallSummary.NoAnswer)
    }

    @Test
    fun `Connected round-trips with duration intact`() {
        val encoded = CallSummaryCodec.encode(CallSummary.Connected(durationSeconds = 83))
        assertThat(encoded).isEqualTo("connected:83")
        assertThat(CallSummaryCodec.decode(encoded)).isEqualTo(CallSummary.Connected(83))
    }

    @Test
    fun `Connected round-trips edge cases`() {
        listOf(0L, 1L, Long.MAX_VALUE).forEach { seconds ->
            val encoded = CallSummaryCodec.encode(CallSummary.Connected(seconds))
            assertThat(CallSummaryCodec.decode(encoded)).isEqualTo(CallSummary.Connected(seconds))
        }
    }

    @Test
    fun `unknown payloads decode to null`() {
        assertThat(CallSummaryCodec.decode("")).isNull()
        assertThat(CallSummaryCodec.decode("garbage")).isNull()
        // Future-shape value the current build doesn't understand
        assertThat(CallSummaryCodec.decode("v2:connected:83:hd")).isNull()
    }

    @Test
    fun `connected payload with non-numeric duration decodes to null`() {
        assertThat(CallSummaryCodec.decode("connected:abc")).isNull()
        assertThat(CallSummaryCodec.decode("connected:")).isNull()
    }
}
