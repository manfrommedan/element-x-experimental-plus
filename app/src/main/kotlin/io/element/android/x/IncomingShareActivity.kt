/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x

import android.os.Bundle
import androidx.activity.OnBackPressedCallback

/**
 * Hosts the ACTION_SEND / ACTION_SEND_MULTIPLE intent filters in a separate task affinity
 * so that pressing Back returns to the calling app (e.g. Telegram), not into the user's main
 * Element session. Subclasses MainActivity so all share-handling logic stays shared.
 *
 * Back intercept: any Back press finishes the activity immediately, returning the user to the
 * caller. The share flow is fire-and-forget by design (WhatsApp / Telegram pattern) - users
 * pick a room, send, and exit; if they want to keep interacting with Element they open the
 * app normally from the launcher.
 */
class IncomingShareActivity : MainActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(enabled = true) {
                override fun handleOnBackPressed() {
                    finishAndRemoveTask()
                }
            },
        )
    }
}
