/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.selfpush

import android.app.Activity
import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import io.element.android.features.enterprise.api.AppStartupHook
import io.element.android.libraries.di.annotations.ApplicationContext

/**
 * Starts the built-in push listener when the app comes up with live registrations.
 * Without this the stream would only come up on registration, on boot, or when a
 * watchdog fires, leaving a cold app start silent in between.
 */
@ContributesIntoSet(AppScope::class)
class SelfPushStartupHook(
    @ApplicationContext private val context: Context,
    private val selfPushStore: SelfPushStore,
) : AppStartupHook {
    override suspend fun onAppStartup(activity: Activity) {
        if (selfPushStore.getAllRegistrations().isNotEmpty()) {
            SelfPushSubscriberService.start(context)
        }
    }
}
