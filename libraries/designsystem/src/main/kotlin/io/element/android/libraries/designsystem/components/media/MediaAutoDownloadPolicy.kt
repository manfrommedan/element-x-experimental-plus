/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.designsystem.components.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.element.android.libraries.core.extensions.runCatchingExceptions

// True when media (image / video thumbnails, static map previews) should be
// fetched automatically; false when the user has enabled "wifi-only auto
// download" and the device is currently on mobile data. Defaults to true so
// consumers that aren't wrapped in the provider behave like upstream.
val LocalAutoLoadMedia = compositionLocalOf { true }

@Composable
fun rememberIsConnectedToWifi(): Boolean {
    val context = LocalContext.current
    var isWifi by remember { mutableStateOf(currentIsWifi(context)) }
    DisposableEffect(context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isWifi = currentIsWifi(context)
            }

            override fun onLost(network: Network) {
                isWifi = currentIsWifi(context)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                isWifi = currentIsWifi(context)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatchingExceptions { cm?.registerNetworkCallback(request, callback) }
        onDispose {
            runCatchingExceptions { cm?.unregisterNetworkCallback(callback) }
        }
    }
    return isWifi
}

private fun currentIsWifi(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}
