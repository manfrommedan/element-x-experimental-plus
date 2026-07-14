/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.androidutils.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Browser
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsSession
import androidx.core.net.toUri
import io.element.android.libraries.androidutils.mxtr.MxtrBridge
import io.element.android.libraries.androidutils.system.openUrlInExternalApp
import timber.log.Timber
import java.util.Locale

/**
 * Open url in custom tab or, if not available, in the default browser.
 * If several compatible browsers are installed, the user will be proposed to choose one.
 * Ref: https://developer.chrome.com/multidevice/android/customtabs.
 */
fun Activity.openUrlInChromeCustomTab(
    session: CustomTabsSession?,
    darkTheme: Boolean,
    url: String
) {
    try {
        CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(
                CustomTabColorSchemeParams.Builder()
                    // TODO .setToolbarColor(ThemeUtils.getColor(context, android.R.attr.colorBackground))
                    // TODO .setNavigationBarColor(ThemeUtils.getColor(context, android.R.attr.colorBackground))
                    .build()
            )
            .setColorScheme(
                when (darkTheme) {
                    false -> CustomTabsIntent.COLOR_SCHEME_LIGHT
                    true -> CustomTabsIntent.COLOR_SCHEME_DARK
                }
            )
            .setShareIdentityEnabled(false)
            // Note: setting close button icon does not work
            // .setCloseButtonIcon(BitmapFactory.decodeResource(context.resources, R.drawable.ic_back_24dp))
            // .setStartAnimations(context, R.anim.enter_fade_in, R.anim.exit_fade_out)
            // .setExitAnimations(context, R.anim.enter_fade_in, R.anim.exit_fade_out)
            .apply { session?.let { setSession(it) } }
            .build()
            .apply {
                // Disable download button
                intent.putExtra("org.chromium.chrome.browser.customtabs.EXTRA_DISABLE_DOWNLOAD_BUTTON", true)
                // Disable bookmark button
                intent.putExtra("org.chromium.chrome.browser.customtabs.EXTRA_DISABLE_STAR_BUTTON", true)
                intent.putExtra(Browser.EXTRA_HEADERS, Bundle().apply {
                    putString("Accept-Language", Locale.getDefault().toLanguageTag())
                })
            }
            .launchUrl(this, url.toUri())
    } catch (_: ActivityNotFoundException) {
        openUrlInExternalApp(url)
    }
}

private const val MXTR_BROWSER_FQCN = "io.element.android.features.login.impl.mxtr.MxtrBrowserActivity"
private const val MXTR_BROWSER_EXTRA_URL = "extra_url"

/**
 * URL open entry-point that respects the mxtr on/off preference: when enabled,
 * routes the open through an in-app WebView (MxtrBrowserActivity) so requests
 * traverse the local mxtr CONNECT listener instead of escaping via a system
 * Chrome Custom Tab. When disabled (or the bridge has not been initialised,
 * e.g. test harness), behaviour is identical to upstream Element X.
 *
 * The bridge contract takes [android.content.Context] per call so this helper
 * never retains an Activity reference past the launch site.
 */
fun Activity.openUrlInMxtrAwareCustomTab(
    session: CustomTabsSession?,
    darkTheme: Boolean,
    url: String,
) {
    val mxtrOn = MxtrBridge.state?.isEnabled(applicationContext) == true
    if (mxtrOn) {
        try {
            val intent = Intent().apply {
                setClassName(packageName, MXTR_BROWSER_FQCN)
                putExtra(MXTR_BROWSER_EXTRA_URL, url)
            }
            startActivity(intent)
            return
        } catch (e: Throwable) {
            Timber.w(e, "mxtr browser launch failed; falling back to system custom tab")
        }
    }
    openUrlInChromeCustomTab(session, darkTheme, url)
}
