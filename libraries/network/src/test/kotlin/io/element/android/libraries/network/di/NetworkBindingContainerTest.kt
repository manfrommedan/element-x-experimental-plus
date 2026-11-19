/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.network.di

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Test
import timber.log.Timber

class NetworkBindingContainerTest {
    @Test
    fun `authorization header is redacted from HTTP logs`() {
        val messages = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                messages.add(message)
            }
        }
        Timber.plant(tree)

        try {
            val loggingInterceptor = NetworkBindingContainer.providesHttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            }
            val client = OkHttpClient.Builder()
                .addInterceptor(loggingInterceptor)
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("test")
                        .body("".toResponseBody())
                        .build()
                }
                .build()
            val request = Request.Builder()
                .url("https://example.org")
                .header("Authorization", "Bearer secret-token")
                .build()

            client.newCall(request).execute().close()

            assertThat(messages.joinToString("\n")).doesNotContain("secret-token")
        } finally {
            Timber.uproot(tree)
        }
    }
}
