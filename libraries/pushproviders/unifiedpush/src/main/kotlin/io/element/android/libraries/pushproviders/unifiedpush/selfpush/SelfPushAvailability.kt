/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.selfpush

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Synchronous view of the embedded-push Labs flag. The distributor list is read
 * synchronously, while the flag service speaks in flows, so the flag is tracked here
 * once for the process. Reads before the first emission see the flag's default, false.
 */
interface SelfPushAvailability {
    fun isEnabled(): Boolean
}

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class DefaultSelfPushAvailability(
    featureFlagService: FeatureFlagService,
    @AppCoroutineScope appCoroutineScope: CoroutineScope,
) : SelfPushAvailability {
    @Volatile
    private var enabled: Boolean = false

    init {
        appCoroutineScope.launch {
            featureFlagService.isFeatureEnabledFlow(FeatureFlags.BuiltInPush).collect { enabled = it }
        }
    }

    override fun isEnabled(): Boolean = enabled
}
