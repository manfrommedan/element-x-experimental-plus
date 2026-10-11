/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.androidutils.system.getApplicationLabel
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.pushproviders.api.Distributor
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushAvailability
import io.element.android.libraries.ui.strings.CommonStrings
import org.unifiedpush.android.connector.UnifiedPush

interface UnifiedPushDistributorProvider {
    fun getDistributors(): List<Distributor>
}

@ContributesBinding(AppScope::class)
class DefaultUnifiedPushDistributorProvider(
    @ApplicationContext private val context: Context,
    private val selfPushAvailability: SelfPushAvailability,
) : UnifiedPushDistributorProvider {
    override fun getDistributors(): List<Distributor> {
        val distributors = UnifiedPush.getDistributors(context)
        // The app itself is offered first when the built-in push listener is enabled: with the
        // UnifiedPush distributor action declared in the manifest the app shows up here anyway,
        // so it is excluded below either way and re-added with a readable name.
        val builtIn = if (selfPushAvailability.isEnabled()) {
            listOf(Distributor(context.packageName, context.getString(CommonStrings.common_built_in_push)))
        } else {
            emptyList()
        }
        return builtIn + distributors.mapNotNull {
            if (it == context.packageName) {
                // Exclude self
                null
            } else {
                Distributor(it, context.getApplicationLabel(it))
            }
        }
    }
}
