/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.mxtr

import java.math.BigInteger

internal object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEX = IntArray(128) { -1 }.also {
        for (i in ALPHABET.indices) it[ALPHABET[i].code] = i
    }

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) zeros++
        val rest = input.copyOfRange(zeros, input.size)
        var n = BigInteger(1, rest)
        val sb = StringBuilder()
        val base = BigInteger.valueOf(58)
        while (n.signum() > 0) {
            val (q, r) = n.divideAndRemainder(base)
            sb.append(ALPHABET[r.toInt()])
            n = q
        }
        repeat(zeros) { sb.append(ALPHABET[0]) }
        return sb.reverse().toString()
    }

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        var zeros = 0
        while (zeros < input.length && input[zeros] == ALPHABET[0]) zeros++

        var n = BigInteger.ZERO
        val base = BigInteger.valueOf(58)
        for (c in input) {
            val v = if (c.code < INDEX.size) INDEX[c.code] else -1
            require(v >= 0) { "invalid base58 char '$c'" }
            n = n.multiply(base).add(BigInteger.valueOf(v.toLong()))
        }
        val bytes = if (n.signum() == 0) ByteArray(0) else n.toByteArray()
        // BigInteger.toByteArray includes a leading 0x00 sign byte for positive values
        // with high bit set; trim it.
        val trimmed = if (bytes.isNotEmpty() && bytes[0].toInt() == 0) {
            bytes.copyOfRange(1, bytes.size)
        } else {
            bytes
        }
        return ByteArray(zeros) + trimmed
    }
}
