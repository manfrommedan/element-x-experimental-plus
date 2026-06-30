/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.urlpreview

import android.annotation.SuppressLint
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Inline YouTube player that loads the official `youtube.com/embed` page directly in a WebView, so
 * the embed has a real `youtube.com` origin (which YouTube serves) rather than the local-HTML embed
 * that YouTube has started rejecting. Playback stays in the timeline, without leaving the app.
 *
 * The WebView is destroyed when it leaves the composition, which stops playback as soon as the card
 * scrolls away. When the anti-censorship proxy is on it rides the process-wide WebView proxy, so the
 * video loads through the proxy rather than from the device IP.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun YouTubeVideoPlayer(
    videoId: String,
    modifier: Modifier = Modifier,
    onPlayerError: (String) -> Unit = {},
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Allow the embed to start playing without a further tap, since the user already
                // tapped the play button to open the player.
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) {
                            onPlayerError(error.description?.toString().orEmpty().ifBlank { "load error" })
                        }
                    }
                }
                loadUrl("https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1&rel=0")
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        },
    )
}
