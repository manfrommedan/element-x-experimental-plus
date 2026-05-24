/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.location.api.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.element.android.compound.theme.ElementTheme

/**
 * Builds a style URI for a MapLibre compatible tile server.
 *
 * Used for rendering dynamic maps.
 */
interface TileServerStyleUriBuilder {
    fun build(
        darkMode: Boolean,
    ): String
}

// Interactive map (live-location picker + view) uses MapTiler. MapTiler's
// free tier serves style.json + raw vector tiles fine; only its Static Maps
// API is paid (see GeoapifyStaticMapUrlBuilder for why static previews use
// Geoapify instead). Geoapify's hosted style.json hardcodes a public demo
// apiKey inside its tile/sprite/glyph URLs, so MapLibre can't be pointed at
// it without burning someone else's quota.
fun TileServerStyleUriBuilder(): TileServerStyleUriBuilder = MapTilerTileServerStyleUriBuilder()

/**
 * Provides and remembers a style URI for a MapLibre compatible tile server.
 *
 * Used for rendering dynamic maps.
 */
@Composable
fun rememberTileStyleUrl(): String {
    val darkMode = !ElementTheme.isLightTheme
    return remember(darkMode) {
        TileServerStyleUriBuilder().build(darkMode)
    }
}
