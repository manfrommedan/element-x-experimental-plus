/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.mxtr

import android.content.Context
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import io.element.android.libraries.matrix.impl.mxtr.MxtrConfig
import io.element.android.libraries.matrix.impl.mxtr.MxtrPreferencesStore
import timber.log.Timber
import java.util.concurrent.Executors

object MxtrWebViewProxy {
    private const val TAG = "MxtrWebViewProxy"
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mxtr-webview-proxy").apply { isDaemon = true }
    }

    // Routes every WebView's HTTP/HTTPS through our local mxtr HTTP CONNECT proxy
    // on 127.0.0.1:1984. Process-wide; covers Element Call WebView, OIDC WebView,
    // and any future WebView the app spawns.
    fun applyGlobally(context: Context) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Timber.tag(TAG).w("PROXY_OVERRIDE not supported on this WebView")
            return
        }
        val cfg = MxtrPreferencesStore(context.applicationContext).snapshotBlocking()
        if (!cfg.enabled || cfg.data == null) {
            Timber.tag(TAG).i("mxtr not enabled or unconfigured; clearing any prior WebView proxy override")
            ProxyController.getInstance().clearProxyOverride(executor) {}
            return
        }
        val proxy = "${MxtrConfig.LOCAL_PROXY_HOST}:${MxtrConfig.activeLocalPort()}"
        val config = ProxyConfig.Builder()
            .addProxyRule(proxy)
            // Bypass private / link-local addresses so WebRTC LAN/internal ICE
            // candidates don't get routed through the HTTP CONNECT proxy (which
            // can't tunnel UDP and breaks fast media path). Public addresses
            // (real TURN servers) still go through mxtr.
            .addBypassRule("<local>")
            .addBypassRule("127.0.0.1")
            .addBypassRule("10.0.0.0/8")
            .addBypassRule("172.16.0.0/12")
            .addBypassRule("192.168.0.0/16")
            .addBypassRule("169.254.0.0/16")
            .addBypassRule("fd00::/8")
            .addBypassRule("fe80::/10")
            .addDirect() // fallback if local proxy unreachable
            .build()
        ProxyController.getInstance().setProxyOverride(config, executor) {
            Timber.tag(TAG).i("WebView proxy set to %s", proxy)
        }
    }
}
