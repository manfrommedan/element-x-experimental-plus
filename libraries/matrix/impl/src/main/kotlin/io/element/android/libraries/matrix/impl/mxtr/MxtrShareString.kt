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
    // Optional SNI hostname clients should present in the outer TLS
    // ClientHello so SNI matches the cert Subject the server emits. When
    // null/empty, MxtrSession.connect falls back to the IP literal — fine
    // for self-signed-rotating-CN deployments that don't care about SNI
    // parity, suspicious for any real-cert deployment.
    val sni: String? = null,
) {
    fun pskBytes(): ByteArray = Base58.decode(pskBase58)
    fun pskHex(): String = pskBytes().joinToString("") { "%02x".format(it.toInt() and 0xff) }

    // ME-01: IPv6 literals must be bracketed when emitted back as a URL so the
    // parser doesn't mistake colons in the address for the port separator.
    // `java.net.URI` strips brackets when parsing `host`, so we re-add them.
    fun toShareString(): String {
        val emittedHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
        val base = "mxtr://$pskBase58@$emittedHost:$port"
        return if (!sni.isNullOrEmpty()) "$base?sni=$sni" else base
    }
}

// Explicit parentheses below document the on-the-wire byte/bit layout; keep them.
@Suppress("UnnecessaryParentheses")
object MxtrShareString {
    // Format: mxtr://<base58-32B-psk>@<ipv4-or-bracketed-ipv6>:<port>?sni=<edge-name>
    // Hostnames in the host position are refused so InetSocketAddress(host, port)
    // never triggers DNS — RU DNS poisoning therefore cannot redirect mxtr traffic
    // to a sinkhole. The optional ?sni= query lets the server prescribe what the
    // client should present in the outer TLS ClientHello so SNI matches the cert
    // CN the server emits.
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
            if (!isIpLiteral(host)) return null
            val port = uri.port.takeIf { it > 0 } ?: return null
            // Validate base58 by attempting decode and length check (32-byte PSK expected).
            val bytes = Base58.decode(userInfo)
            if (bytes.size != 32) return null
            // Optional ?sni=<hostname> query. Refuse if SNI is itself an IP,
            // since TLS spec forbids IP-literal SNI; refuse malformed hostname.
            val sni = parseSniQuery(uri.rawQuery)
            if (sni != null && !isValidHostname(sni)) return null
            MxtrShareData(host = host, port = port, pskBase58 = userInfo, sni = sni)
        } catch (_: Throwable) {
            null
        }
    }

    // IPv4 dotted-quad: each octet 0-255, no leading zeros longer than the digit itself.
    private val IPV4_RE = Regex("^(?:(?:25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)$")

    // IPv6 in any RFC 4291 form, including ::, compressed, and IPv4-mapped tails.
    // URI.host strips outer [] brackets, so we test bare-form here.
    private val IPV6_RE = Regex(
        "^(" +
            "([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|" + // full 8 groups
            "([0-9a-fA-F]{1,4}:){1,7}:|" + // compressed trail ::
            "([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|" +
            "([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|" +
            "([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|" +
            "([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|" +
            "([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|" +
            "[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|" +
            ":((:[0-9a-fA-F]{1,4}){1,7}|:)" +
        ")$"
    )

    // IP-literal check that does not call InetAddress.getByName (which would
    // trigger DNS for a non-literal string). Regex-only: matches IPv4
    // dotted-quad or IPv6 (compressed or full).
    private fun isIpLiteral(host: String): Boolean {
        if (host.isEmpty()) return false
        return IPV4_RE.matches(host) || IPV6_RE.matches(host)
    }

    private fun parseSniQuery(rawQuery: String?): String? {
        if (rawQuery.isNullOrEmpty()) return null
        for (kv in rawQuery.split('&')) {
            val eq = kv.indexOf('=')
            if (eq <= 0) continue
            val key = kv.substring(0, eq)
            if (key == "sni") {
                val v = kv.substring(eq + 1)
                if (v.isEmpty()) return null
                return v
            }
        }
        return null
    }

    // RFC 1035 hostname syntax check. Refuses IP literals so attacker can't
    // smuggle an IP through ?sni= (TLS forbids IP in SNI; would be a tell).
    private fun isValidHostname(s: String): Boolean {
        if (s.isEmpty() || s.length > 253) return false
        if (IPV4_RE.matches(s) || IPV6_RE.matches(s)) return false
        for (label in s.split('.')) {
            if (label.isEmpty() || label.length > 63) return false
            if (label.first() == '-' || label.last() == '-') return false
            for (c in label) {
                val ok = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '-'
                if (!ok) return false
            }
        }
        return true
    }

    fun fromHex(host: String, port: Int, pskHex: String, sni: String? = null): MxtrShareData {
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
        val cleanSni = sni?.takeIf { it.isNotBlank() }?.let {
            require(isValidHostname(it)) { "SNI must be a DNS hostname, not $it" }
            it
        }
        return MxtrShareData(host = host, port = port, pskBase58 = Base58.encode(bytes), sni = cleanSni)
    }

    /**
     * True iff [input] is a syntactically valid share-string. Cheap; safe to
     * call on every keystroke for inline UI validation. Does NOT verify the
     * server is reachable - only that we'd accept it at connect-time.
     */
    fun isValid(input: String): Boolean = parse(input) != null

    fun isValidIpLiteral(host: String): Boolean = isIpLiteral(host)

    fun isValidHostnamePublic(s: String): Boolean = isValidHostname(s)
}
