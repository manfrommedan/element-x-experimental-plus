/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.login.impl.mxtr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.getSystemService
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.androidutils.mxtr.MxtrBridge
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.impl.mxtr.MxtrConfig
import timber.log.Timber
import java.util.concurrent.Executors

class MxtrBrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrEmpty()) {
            finish()
            return
        }
        setContent {
            ElementTheme {
                BrowserScreen(initialUrl = url, onClose = { finish() })
            }
        }
    }

    companion object {
        const val EXTRA_URL = "extra_url"

        internal val EXECUTOR = Executors.newSingleThreadExecutor { r ->
            Thread(r, "mxtr-browser-proxy").apply { isDaemon = true }
        }
    }
}

@Composable
private fun BrowserScreen(initialUrl: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var progress by remember { mutableFloatStateOf(0f) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val resources = LocalResources.current
    val packageName = context.packageName
    val redirectScheme = remember(resources, packageName) {
        val resId = resources.getIdentifier("login_redirect_scheme", "string", packageName)
        if (resId != 0) resources.getString(resId) else "io.element.android"
    }

    val currentOnClose by rememberUpdatedState(onClose)
    LaunchedEffect(Unit) {
        val activity = context as? ComponentActivity ?: return@LaunchedEffect
        activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webViewRef
                if (wv != null && wv.canGoBack()) wv.goBack() else currentOnClose()
            }
        })
    }

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ElementTheme.colors.bgCanvasDefault)
            .padding(top = topInset),
    ) {
        BrowserTopBar(
            url = currentUrl,
            onClose = onClose,
            onReload = { webViewRef?.reload() },
            onShare = {
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, currentUrl)
                }
                context.startActivity(Intent.createChooser(sendIntent, null))
            },
            onCopy = {
                context.getSystemService<ClipboardManager>()?.setPrimaryClip(
                    ClipData.newPlainText("url", currentUrl)
                )
                Toast.makeText(context, "URL скопирован", Toast.LENGTH_SHORT).show()
            },
            onOpenExternal = {
                // Restrict to http/https; without this a page can JS-redirect to
                // `intent://...#Intent;...end` and trick the user into firing an
                // arbitrary system Intent via this button (WR-08).
                val parsed = Uri.parse(currentUrl)
                val scheme = parsed.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") {
                    val ext = Intent(Intent.ACTION_VIEW, parsed)
                    if (ext.resolveActivity(context.packageManager) != null) {
                        context.startActivity(ext)
                    }
                }
            },
        )
        if (progress > 0f && progress < 1f) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        } else {
            Box(modifier = Modifier.fillMaxWidth().height(2.dp))
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    val settings: WebSettings = settings
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.userAgentString = settings.userAgentString + " ElementX/in-app"

                    CookieManager.getInstance().setAcceptCookie(true)
                    // LO3-07: first-party cookies only. OIDC redirect flows
                    // (Authentik / Keycloak / etc.) don't need third-party,
                    // and enabling them broadens the in-app browser's cookie
                    // surface to tracker pixels on the consent page.
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val target = request.url ?: return false
                            // OIDC redirect is `<scheme>:/<path>?...` per DefaultOidcRedirectUrlProvider
                            // (RFC 8252 §7.1 private-use URI scheme). Match exactly:
                            //   scheme == expected redirect scheme
                            //   AND no authority component (rejects `scheme://attacker/path?code=evil`
                            //   where attacker-controlled JS could swap the host)
                            //   AND scheme-specific-part starts with single `/`
                            // This blocks the scheme-prefix forgery class (CR-03).
                            val matches = target.scheme == redirectScheme &&
                                target.authority.isNullOrEmpty() &&
                                target.schemeSpecificPart?.startsWith("/") == true
                            if (matches) {
                                handOff(ctx, target, onClose)
                                return true
                            }
                            return false
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            currentUrl = url ?: currentUrl
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            currentUrl = url ?: currentUrl
                            progress = 1f
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress / 100f
                        }
                    }

                    configureProxyThenLoad(this, initialUrl)
                    webViewRef = this
                }
            },
        )
    }
}

@Composable
private fun BrowserTopBar(
    url: String,
    onClose: () -> Unit,
    onReload: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val host = remember(url) {
        try {
            Uri.parse(url).host ?: url
        } catch (_: Throwable) {
            url
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        IconButton(onClick = onClose) {
            Icon(CompoundIcons.Close(), contentDescription = "Закрыть")
        }
        Text(
            text = host,
            style = ElementTheme.typography.fontBodyMdMedium,
            color = ElementTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onReload) {
            Icon(CompoundIcons.Restart(), contentDescription = "Перезагрузить")
        }
        IconButton(onClick = onCopy) {
            Icon(CompoundIcons.Copy(), contentDescription = "Скопировать URL")
        }
        IconButton(onClick = onShare) {
            Icon(CompoundIcons.Share(), contentDescription = "Поделиться")
        }
        IconButton(onClick = onOpenExternal) {
            Icon(CompoundIcons.PopOut(), contentDescription = "Открыть во внешнем браузере")
        }
    }
}

private fun configureProxyThenLoad(webView: WebView, url: String) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
        webView.loadUrl(url)
        return
    }
    val mxtrOn = MxtrBridge.state?.isEnabled(webView.context.applicationContext) == true
    if (!mxtrOn) {
        // mxtr off → don't install any override here; the process-wide config
        // set by MxtrWebViewProxy.applyGlobally is correct (cleared) for our
        // off state. Loading directly preserves whatever the upstream stack
        // expects (ME-04 + on/off-gate consistency).
        webView.loadUrl(url)
        return
    }
    // Mirror MxtrWebViewProxy.applyGlobally's bypass rules so we don't clobber
    // the WebRTC LAN/STUN exemption (Kotlin CR-01). Without these, any visit
    // to an in-app browser tab would re-set the process-wide proxy without
    // <local> / RFC1918 / link-local bypasses and break Element Call media
    // for the rest of the process lifetime.
    val proxy = "${MxtrConfig.LOCAL_PROXY_HOST}:${MxtrConfig.activeLocalPort()}"
    val config = ProxyConfig.Builder()
        .addProxyRule(proxy)
        .addBypassRule("<local>")
        .addBypassRule("127.0.0.1")
        .addBypassRule("10.0.0.0/8")
        .addBypassRule("172.16.0.0/12")
        .addBypassRule("192.168.0.0/16")
        .addBypassRule("169.254.0.0/16")
        .addBypassRule("fd00::/8")
        .addBypassRule("fe80::/10")
        .addDirect()
        .build()
    ProxyController.getInstance().setProxyOverride(config, MxtrBrowserActivity.EXECUTOR) {
        webView.post { webView.loadUrl(url) }
    }
    // ME3-09: if the ProxyController callback never fires (WebView service
    // crash, rejected config), the loadUrl above never runs and the user sees
    // a blank tab. Fallback: if 5s in nothing loaded, fire loadUrl anyway.
    webView.postDelayed({
        if (webView.url.isNullOrEmpty()) webView.loadUrl(url)
    }, 5_000)
}

private fun handOff(context: Context, uri: Uri, onClose: () -> Unit) {
    Timber.tag("MxtrBrowser").d("OIDC-style redirect captured: %s", uri.scheme)
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        setPackage(context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    context.startActivity(intent)
    onClose()
}
