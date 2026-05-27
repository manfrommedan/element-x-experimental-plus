/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.mxtr

object MxtrConfig {
    const val LOCAL_PROXY_HOST = "127.0.0.1"
    const val LOCAL_PROXY_PORT = 1984

    fun proxyUrl(): String = "http://$LOCAL_PROXY_HOST:$LOCAL_PROXY_PORT"
}
