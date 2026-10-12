/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.notifications.selfpush

/**
 * Valid means: https, no trailing slash, parseable host. http is rejected on purpose: the push
 * content would cross the network unencrypted.
 */
internal fun isValidSelfPushServerUrl(url: String): Boolean {
    if (!url.startsWith("https://")) return false
    if (url.endsWith("/")) return false
    val host = url.removePrefix("https://").substringBefore('/')
    return host.isNotBlank() && !host.contains(' ')
}
