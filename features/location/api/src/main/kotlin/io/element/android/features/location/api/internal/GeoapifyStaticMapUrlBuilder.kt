/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.location.api.internal

import io.element.android.features.location.api.BuildConfig
import kotlin.math.roundToInt

/**
 * Builds a URL for Geoapify's Static Maps API.
 *
 * https://apidocs.geoapify.com/docs/maps/static-map/
 *
 * Endpoint format:
 *   GET /v1/staticmap?style=<style>&width=<W>&height=<H>&center=lonlat:<lon>,<lat>&zoom=<z>&apiKey=<key>
 *
 * Picked over MapTiler because MapTiler's free tier denies static map
 * rendering (HTTP 403 "Access to rendered maps not allowed"), whereas
 * Geoapify allows 3000 requests/day on the free tier.
 */
internal class GeoapifyStaticMapUrlBuilder(
    private val baseUrl: String,
    private val apiKeyProvider: () -> String,
    private val lightStyle: String,
    private val darkStyle: String,
) : StaticMapUrlBuilder {
    constructor() : this(
        baseUrl = BuildConfig.GEOAPIFY_STATIC_BASE_URL.removeSuffix("/"),
        apiKeyProvider = { GeoapifyKeyHolder.current() },
        lightStyle = BuildConfig.GEOAPIFY_LIGHT_STYLE,
        darkStyle = BuildConfig.GEOAPIFY_DARK_STYLE,
    )

    override fun build(
        lat: Double,
        lon: Double,
        zoom: Double,
        darkMode: Boolean,
        width: Int,
        height: Int,
        density: Float
    ): String {
        val style = if (darkMode) darkStyle else lightStyle
        val finalZoom = zoom.coerceIn(zoomRange)
        val is2x = density >= 2f

        val (finalWidth, finalHeight) = coerceWidthAndHeight(
            width = (width / density).roundToInt(),
            height = (height / density).roundToInt(),
        )

        // Geoapify supports a scaleFactor parameter (1 or 2) to request
        // hi-dpi tiles independently of the width/height in CSS pixels.
        val scaleFactor = if (is2x) 2 else 1

        return buildString {
            append(baseUrl)
            append("?style=").append(style)
            append("&width=").append(finalWidth)
            append("&height=").append(finalHeight)
            append("&center=lonlat:").append(lon).append(',').append(lat)
            append("&zoom=").append(finalZoom)
            append("&scaleFactor=").append(scaleFactor)
            append("&format=jpeg")
            append("&apiKey=").append(apiKeyProvider())
        }
    }

    override fun isServiceAvailable() = apiKeyProvider().isNotEmpty()
}

private fun coerceWidthAndHeight(width: Int, height: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 0 to 0
    val aspectRatio = width.toDouble() / height.toDouble()
    return if (width >= height) {
        width.coerceIn(widthHeightRange).let { coercedWidth ->
            coercedWidth to (coercedWidth / aspectRatio).roundToInt()
        }
    } else {
        height.coerceIn(widthHeightRange).let { coercedHeight ->
            (coercedHeight * aspectRatio).roundToInt() to coercedHeight
        }
    }
}

// Geoapify static maps support widths/heights up to 8192 px on paid plans;
// stay conservative to match free-tier behaviour and avoid surprise quota
// hits from very large requests on hi-dpi screens.
private val widthHeightRange = 1..2048
private val zoomRange = 0.0..20.0
