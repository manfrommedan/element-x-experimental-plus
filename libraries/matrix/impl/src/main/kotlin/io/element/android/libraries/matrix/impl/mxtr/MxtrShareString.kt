/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.mxtr

import java.net.URI

data class MxtrShareData(
    val host: String,
    val port: Int,
    val pskBase58: String,
) {
    fun pskBytes(): ByteArray = Base58.decode(pskBase58)
    fun pskHex(): String = pskBytes().joinToString("") { "%02x".format(it.toInt() and 0xff) }

    // ME-01: IPv6 literals must be bracketed when emitted back as a URL so the
    // parser doesn't mistake colons in the address for the port separator.
    // `java.net.URI` strips brackets when parsing `host`, so we re-add them.
    fun toShareString(): String {
        val emittedHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
        return "mxtr://$pskBase58@$emittedHost:$port"
    }
}

object MxtrShareString {
    // Format: mxtr://<base58-psk>@<host>:<port>
    // Example: mxtr://DhKnMP3xAjVgZWqRcFbS6T8YuMcwQ7N1V2K7TVpRr3vA@vps.example.org:9290
    fun parse(input: String): MxtrShareData? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val uri = URI(trimmed)
            if (uri.scheme != "mxtr") return null
            val userInfo = uri.userInfo ?: return null
            // ME3-07: reject `user:password@host:port` form. mxtr's userInfo
            // is base58-encoded PSK with no colons; if we see one the input
            // is malformed (e.g. accidentally a stale HTTP-style URL).
            if (userInfo.contains(':')) return null
            val host = uri.host ?: return null
            val port = uri.port.takeIf { it > 0 } ?: return null
            // Validate base58 by attempting decode and length check (32-byte PSK expected).
            val bytes = Base58.decode(userInfo)
            if (bytes.size != 32) return null
            MxtrShareData(host = host, port = port, pskBase58 = userInfo)
        } catch (_: Throwable) {
            null
        }
    }

    fun fromHex(host: String, port: Int, pskHex: String): MxtrShareData {
        // LO3-10: 32-byte PSK == 64 hex chars exactly; reject odd length and
        // any non-hex character so the caller doesn't get a silently-garbled
        // PSK out of the decoder.
        require(pskHex.length == 64) { "PSK hex must be 64 chars (32 bytes); got ${pskHex.length}" }
        val bytes = ByteArray(32)
        for (i in bytes.indices) {
            val hi = Character.digit(pskHex[i * 2], 16)
            val lo = Character.digit(pskHex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "PSK hex contains non-hex character at position ${i * 2}" }
            bytes[i] = ((hi shl 4) or lo).toByte()
        }
        return MxtrShareData(host = host, port = port, pskBase58 = Base58.encode(bytes))
    }
}
