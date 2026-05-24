/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.location.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.location.api.BuildConfig
import io.element.android.features.location.api.internal.MapTilerKeyHolder
import io.element.android.libraries.preferences.test.InMemoryAppPreferencesStore
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultLocationServiceTest {
    @Test
    fun `isServiceAvailable falls back to BuildConfig when no override set`() = runTest {
        MapTilerKeyHolder.set(null)
        val locationService = DefaultLocationService(
            appPreferencesStore = InMemoryAppPreferencesStore(),
            appCoroutineScope = TestScope(),
        )
        assertThat(locationService.isServiceAvailable()).isEqualTo(
            BuildConfig.MAPTILER_API_KEY.isNotEmpty()
        )
    }

    @Test
    fun `isServiceAvailable reports true when user override is set`() = runTest {
        MapTilerKeyHolder.set("user-supplied-key")
        val locationService = DefaultLocationService(
            appPreferencesStore = InMemoryAppPreferencesStore(),
            appCoroutineScope = TestScope(),
        )
        assertThat(locationService.isServiceAvailable()).isTrue()
        MapTilerKeyHolder.set(null) // reset for other tests
    }
}
