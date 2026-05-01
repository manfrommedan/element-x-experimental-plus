/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.messages.impl.timeline.components

import androidx.compose.runtime.staticCompositionLocalOf
import io.element.android.features.call.api.CallSummary
import io.element.android.features.call.api.CallSummaryStore
import io.element.android.libraries.matrix.api.core.EventId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

val LocalCallSummaryStore = staticCompositionLocalOf<CallSummaryStore> {
    object : CallSummaryStore {
        override suspend fun save(eventId: EventId, summary: CallSummary) = Unit
        override fun observe(eventId: EventId): Flow<CallSummary?> = flowOf(null)
    }
}
