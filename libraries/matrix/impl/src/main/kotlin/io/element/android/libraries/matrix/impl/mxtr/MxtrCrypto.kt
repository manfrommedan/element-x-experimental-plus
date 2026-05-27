/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.mxtr

import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal object MxtrCrypto {
    fun hexDecode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            out[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return out
    }

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    // RFC 5869 HKDF-SHA256.
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val n = (length + 31) / 32
        require(n <= 255) { "hkdf length too large" }
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        for (i in 1..n) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            val copy = minOf(32, length - pos)
            System.arraycopy(t, 0, out, pos, copy)
            pos += copy
        }
        return out
    }

    // ChaCha20-Poly1305 via JCE. API 28+ ships native provider; on older API a
    // BouncyCastle/Tink provider would need to be registered (not done here).
    fun chacha20Poly1305Encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val c = Cipher.getInstance("ChaCha20-Poly1305")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        return c.doFinal(plaintext)
    }

    fun chacha20Poly1305Decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val c = Cipher.getInstance("ChaCha20-Poly1305")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        return c.doFinal(ciphertext)
    }

    fun frameNonce(seq: Long): ByteArray {
        val n = ByteArray(12)
        n[4] = (seq ushr 56).toByte()
        n[5] = (seq ushr 48).toByte()
        n[6] = (seq ushr 40).toByte()
        n[7] = (seq ushr 32).toByte()
        n[8] = (seq ushr 24).toByte()
        n[9] = (seq ushr 16).toByte()
        n[10] = (seq ushr 8).toByte()
        n[11] = seq.toByte()
        return n
    }

    // SSLSocketFactory that skips cert verification. Server uses a self-signed
    // cert with a rotating CN; PSK in the inner handshake is the real authn.
    // ME3-10: cached at object level. SSLContext.getInstance + SecureRandom
    // seeding can take 50-200ms on Android <26; reconnect storms shouldn't
    // pay that cost on every retry.
    fun insecureSslSocketFactory(): SSLSocketFactory = cachedSocketFactory

    private val cachedSocketFactory: SSLSocketFactory by lazy {
        val tm = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        // Pin TLS 1.3 — see WR-02 / ME3-10.
        val ctx = SSLContext.getInstance("TLSv1.3")
        ctx.init(null, tm, java.security.SecureRandom())
        ctx.socketFactory
    }
}
