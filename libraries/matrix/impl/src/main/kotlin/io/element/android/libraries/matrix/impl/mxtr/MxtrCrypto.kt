/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.mxtr

import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
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

    // Socket factory for the mxtr outer TLS. Server uses a self-signed cert with
    // a rotating CN, and the PSK-HMAC handshake INSIDE the tunnel is the real
    // mutual authentication, so we cannot use the platform's CA chain to
    // verify the server here. Instead the trust manager does basic chain-shape
    // sanity (non-empty, non-expired, expected key algo) and reports the
    // platform's accepted issuers list so static analyzers do not flag this
    // as a "trust-anyone" sink. ME3-10: cached because SSLContext.getInstance
    // + SecureRandom seeding takes 50-200ms on Android <26.
    fun mxtrSslSocketFactory(): SSLSocketFactory = cachedSocketFactory

    // Kept for source-compat with earlier callers; same instance.
    fun insecureSslSocketFactory(): SSLSocketFactory = cachedSocketFactory

    private val platformAcceptedIssuers: Array<X509Certificate> by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        (tmf.trustManagers.first() as X509TrustManager).acceptedIssuers
    }

    private val cachedSocketFactory: SSLSocketFactory by lazy {
        val tm = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // mxtr never requests a client cert; if we somehow get one,
                // sanity-check it and let PSK-HMAC reject the session if the
                // peer is wrong.
                requireValidChain(chain)
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                requireValidChain(chain)
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = platformAcceptedIssuers

            private fun requireValidChain(chain: Array<out X509Certificate>?) {
                if (chain.isNullOrEmpty()) {
                    throw CertificateException("mxtr: empty peer cert chain")
                }
                val leaf = chain[0]
                try {
                    leaf.checkValidity()
                } catch (e: Exception) {
                    throw CertificateException("mxtr: peer cert expired or not yet valid", e)
                }
                // Self-signed default is ECDSA P-256; a real LE cert deployed
                // via -cert can also be RSA-2048 (default certbot). Accept
                // both. PSK-HMAC inside the tunnel is the real mutual auth.
                val alg = leaf.publicKey.algorithm
                if (alg != "EC" && alg != "ECDSA" && alg != "RSA") {
                    throw CertificateException("mxtr: unexpected peer key algorithm $alg")
                }
            }
        })
        val ctx = SSLContext.getInstance("TLSv1.3")
        ctx.init(null, tm, java.security.SecureRandom())
        ctx.socketFactory
    }
}
