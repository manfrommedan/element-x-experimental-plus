/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.selfpush

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.di.annotations.ApplicationContext

/**
 * Pokes the listener so changed settings (server, protocol) take effect on the live streams
 * at once, without waiting for the next registration or watchdog tick.
 */
interface SelfPushStreamRefresher {
    fun refresh()
}

@ContributesBinding(AppScope::class)
class DefaultSelfPushStreamRefresher(
    @ApplicationContext private val context: Context,
) : SelfPushStreamRefresher {
    override fun refresh() {
        SelfPushSubscriberService.start(context)
    }
}
