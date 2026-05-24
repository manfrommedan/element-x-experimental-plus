/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 *
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.location.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.element.android.features.location.api.LocationService
import io.element.android.features.location.api.internal.GeoapifyKeyHolder
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@ContributesBinding(AppScope::class)
@Inject
class DefaultLocationService(
    appPreferencesStore: AppPreferencesStore,
    @AppCoroutineScope appCoroutineScope: CoroutineScope,
) : LocationService {
    init {
        appCoroutineScope.launch {
            appPreferencesStore.getGeoapifyApiKeyFlow().collect { override ->
                GeoapifyKeyHolder.set(override)
            }
        }
    }

    override fun isServiceAvailable(): Boolean = GeoapifyKeyHolder.current().isNotEmpty()
}
