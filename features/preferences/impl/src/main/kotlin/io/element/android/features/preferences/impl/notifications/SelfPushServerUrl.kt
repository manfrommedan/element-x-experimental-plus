/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.notifications

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Default built-in push server, mirrors SelfPushRegistrar.DEFAULT_SERVER (private there).
 */
internal const val DEFAULT_SELF_PUSH_SERVER = "https://ntfy.sh"

/**
 * Validation shared by the view (which blocks submit) and the presenter (which refuses to
 * persist): the built-in push server must be an https URL without a trailing slash.
 */
internal fun isValidSelfPushServerUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (!trimmed.startsWith("https://") || trimmed.endsWith("/")) return false
    return trimmed.toHttpUrlOrNull() != null
}
