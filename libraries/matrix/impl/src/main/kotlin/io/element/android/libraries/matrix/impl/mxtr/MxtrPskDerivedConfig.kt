/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.mxtr

// Per-PSK runtime knobs. Must match the Go server-side derivation in
// cmd/mxtr-server/psk_config.go byte-for-byte so both sides breathe at the
// same cadence. See that file for the why.
data class MxtrPskDerivedConfig(
    val heartbeatMinMs: Int,
    val heartbeatMaxMs: Int,
    val heartbeatPadMin: Int,
    val heartbeatPadMax: Int,
    val idleThresholdMs: Int,
) {
    companion object {
        // Explicit parentheses below document the on-the-wire byte/bit layout; keep them.
        @Suppress("UnnecessaryParentheses")
        fun derive(psk: ByteArray): MxtrPskDerivedConfig {
            val out = MxtrCrypto.hkdfSha256(
                ikm = psk,
                salt = "mxtr-config-v1-salt".toByteArray(),
                info = "mxtr-config-v1".toByteArray(),
                length = 16,
            )
            return MxtrPskDerivedConfig(
                heartbeatMinMs = 20_000 + (out[2].toInt() and 0xFF) * 100,
                heartbeatMaxMs = 45_000 + (out[3].toInt() and 0xFF) * 100,
                heartbeatPadMin = 32 + (out[4].toInt() and 0xFF),
                heartbeatPadMax = 512 + ((((out[5].toInt() and 0xFF) shl 8) or (out[6].toInt() and 0xFF)) % 3584),
                idleThresholdMs = 10_000 + (out[8].toInt() and 0xFF) * 50,
            )
        }
    }
}
