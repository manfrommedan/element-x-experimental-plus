/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:JvmName("GeoapifyTileServerStyleUriBuilderKt")

package io.element.android.features.location.api.internal

import io.element.android.features.location.api.BuildConfig

/**
 * Builds a style.json URL for Geoapify's tile-server, used by the MapLibre
 * SDK to render the interactive map (live-location view, location picker).
 *
 * Endpoint format:
 *   GET /v1/styles/<style>/style.json?apiKey=<key>
 */
internal class GeoapifyTileServerStyleUriBuilder(
    private val baseUrl: String,
    private val apiKeyProvider: () -> String,
    private val lightStyle: String,
    private val darkStyle: String,
) : TileServerStyleUriBuilder {
    constructor() : this(
        baseUrl = BuildConfig.GEOAPIFY_STYLE_BASE_URL.removeSuffix("/"),
        apiKeyProvider = { GeoapifyKeyHolder.current() },
        lightStyle = BuildConfig.GEOAPIFY_LIGHT_STYLE,
        darkStyle = BuildConfig.GEOAPIFY_DARK_STYLE,
    )

    override fun build(darkMode: Boolean): String {
        val style = if (darkMode) darkStyle else lightStyle
        return "$baseUrl/$style/style.json?apiKey=${apiKeyProvider()}"
    }
}
