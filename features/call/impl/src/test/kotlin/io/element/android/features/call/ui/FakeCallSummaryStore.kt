/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.ui

import io.element.android.features.call.api.CallSummary
import io.element.android.features.call.api.CallSummaryStore
import io.element.android.libraries.matrix.api.core.EventId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeCallSummaryStore : CallSummaryStore {
    private val saved = MutableStateFlow<Map<EventId, CallSummary>>(emptyMap())

    /** Test accessor for the summaries persisted via [save]. */
    val savedSummaries: Map<EventId, CallSummary> get() = saved.value

    override suspend fun save(eventId: EventId, summary: CallSummary) {
        saved.value = saved.value + (eventId to summary)
    }

    override fun observe(eventId: EventId): Flow<CallSummary?> =
        saved.map { it[eventId] }
}
