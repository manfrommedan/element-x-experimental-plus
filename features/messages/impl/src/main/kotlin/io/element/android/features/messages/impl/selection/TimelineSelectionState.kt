/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.selection

import androidx.compose.runtime.Immutable
import io.element.android.libraries.matrix.api.core.EventId
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf

/**
 * State of the optional bulk-message selection mode (WhatsApp-style).
 * `isActive` is true while the user is multi-selecting; tap on a message in this mode
 * toggles its membership in [selectedIds] instead of opening the single-message action sheet.
 */
@Immutable
data class TimelineSelectionState(
    val isActive: Boolean = false,
    val selectedIds: ImmutableSet<EventId> = persistentSetOf(),
    val maxSelection: Int = MAX_SELECTION,
) {
    val count: Int get() = selectedIds.size
    val isAtCap: Boolean get() = count >= maxSelection

    companion object {
        const val MAX_SELECTION = 30
    }
}
