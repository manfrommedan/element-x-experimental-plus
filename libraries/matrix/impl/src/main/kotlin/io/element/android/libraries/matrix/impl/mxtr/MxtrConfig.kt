/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.mxtr

import java.util.concurrent.atomic.AtomicInteger

object MxtrConfig {
    const val LOCAL_PROXY_HOST = "127.0.0.1"

    // Preferred bind port. If taken by another local app at startup, the
    // accept loop walks PROBE_PORT_RANGE upward until it lands on a free
    // port and stores it in activeLocalPort. All consumers (matrix-rust-sdk
    // proxy URL, WebView ProxyController, ProxySelector) must read through
    // proxyUrl() / activeLocalPort() so they observe the actually-bound
    // value rather than the constant.
    const val PREFERRED_LOCAL_PROXY_PORT = 1984
    const val PROBE_PORT_RANGE = 10

    private val active = AtomicInteger(PREFERRED_LOCAL_PROXY_PORT)

    fun setActiveLocalPort(port: Int) { active.set(port) }
    fun activeLocalPort(): Int = active.get()

    fun proxyUrl(): String = "http://$LOCAL_PROXY_HOST:${active.get()}"
}
