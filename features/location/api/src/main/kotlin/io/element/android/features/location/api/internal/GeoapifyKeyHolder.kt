/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.location.api.internal

import io.element.android.features.location.api.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Effective Geoapify API key used by both the static-map URL builder and
 * the MapLibre tile-server URL builder.
 *
 * Default is BuildConfig.GEOAPIFY_API_KEY (baked in at compile time from
 * the ELEMENT_ANDROID_GEOAPIFY_API_KEY env var / local.properties). A
 * user-supplied override from Advanced Settings is pushed in via [set]
 * and takes precedence. Blank / null override falls back to BuildConfig.
 */
object GeoapifyKeyHolder {
    private val _key = MutableStateFlow(BuildConfig.GEOAPIFY_API_KEY)
    val key: StateFlow<String> = _key

    fun current(): String = _key.value

    fun set(override: String?) {
        val trimmed = override?.trim()
        _key.value = if (trimmed.isNullOrEmpty()) BuildConfig.GEOAPIFY_API_KEY else trimmed
    }
}
