/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.location.api.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.location.api.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

// Geoapify's hosted style.json hard-codes a public demo apiKey inside the
// referenced tile / sprite / glyph URLs (apiKey=176aeab4...). MapLibre follows
// those URLs verbatim and we'd burn the demo quota instead of ours. We fetch
// style.json with our key and rewrite every embedded apiKey=<32hex> to point
// at the same key, so MapLibre's downstream calls hit our account.
internal object GeoapifyStyleLoader {
    private val cache = ConcurrentHashMap<String, String>()
    private val embeddedKeyRegex = Regex("apiKey=[0-9a-fA-F]{32}")

    suspend fun load(darkMode: Boolean): String? {
        val key = GeoapifyKeyHolder.current().ifEmpty { return null }
        val style = if (darkMode) BuildConfig.GEOAPIFY_DARK_STYLE else BuildConfig.GEOAPIFY_LIGHT_STYLE
        val base = BuildConfig.GEOAPIFY_STYLE_BASE_URL.removeSuffix("/")
        val url = "$base/$style/style.json?apiKey=$key"
        cache[url]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val raw = fetch(url) ?: return@runCatching null
                val rewritten = embeddedKeyRegex.replace(raw, "apiKey=$key")
                cache[url] = rewritten
                rewritten
            }.getOrNull()
        }
    }

    private fun fetch(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            if (conn.responseCode != 200) return null
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

@Composable
fun rememberTileStyleJson(): String? {
    val darkMode = !ElementTheme.isLightTheme
    val apiKey = GeoapifyKeyHolder.current()
    var state by remember(darkMode, apiKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(darkMode, apiKey) {
        state = GeoapifyStyleLoader.load(darkMode)
    }
    return state
}
