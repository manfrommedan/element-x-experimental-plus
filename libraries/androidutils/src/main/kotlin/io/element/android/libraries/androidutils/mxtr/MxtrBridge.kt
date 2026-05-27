/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.androidutils.mxtr

import android.content.Context

// Service-locator bridge from low-level androidutils to the mxtr stack which
// lives higher in the module graph (matrix/impl + features/login/impl).
// androidutils mustn't depend on matrix/impl, so the app sets this object at
// startup and androidutils-level helpers (URL launcher, etc.) consult it.
//
// When state is null the app behaves exactly like upstream Element X. When
// state is set and isEnabled() returns true, mxtr code paths are taken.
object MxtrBridge {
    interface State {
        fun isEnabled(context: Context): Boolean
    }

    @Volatile
    var state: State? = null
}
