/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x

/**
 * Hosts the ACTION_SEND / ACTION_SEND_MULTIPLE intent filters in a separate task affinity
 * so that pressing Back returns to the calling app (e.g. Telegram), not into the user's main
 * Element session. Subclasses MainActivity so all share-handling logic stays shared.
 */
class IncomingShareActivity : MainActivity()
