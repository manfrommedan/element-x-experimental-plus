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

    // The listener binds an OS-assigned ephemeral port (a different one every
    // time the config is applied), not a fixed/predictable one. A local app
    // can't scan a known port (1984/1993/...) to discover or abuse the proxy.
    // The bound value is published via setActiveLocalPort() right after bind;
    // all consumers (matrix-rust-sdk proxy URL, WebView ProxyController,
    // ProxySelector) must read it through proxyUrl() / activeLocalPort()
    // rather than any constant. 0 means "not bound yet".
    private val active = AtomicInteger(0)

    fun setActiveLocalPort(port: Int) {
        active.set(port)
    }
    fun activeLocalPort(): Int = active.get()

    fun proxyUrl(): String = "http://$LOCAL_PROXY_HOST:${active.get()}"
}
