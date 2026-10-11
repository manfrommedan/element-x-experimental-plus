/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.selfpush

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.element.android.libraries.core.extensions.runCatchingExceptions
import timber.log.Timber

/**
 * Restarts the built-in push listener after a reboot. The service stops itself at once
 * when there is nothing to listen to, so this can fire unconditionally.
 */
class SelfPushBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        runCatchingExceptions {
            SelfPushSubscriberService.start(context)
        }.onFailure { Timber.w(it, "Could not restart the built-in push listener on boot") }
    }
}
