/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.call.utils

import io.element.android.features.call.api.CallSummary
import io.element.android.features.call.api.CallSummaryStore
import io.element.android.libraries.matrix.api.core.EventId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeCallSummaryStore : CallSummaryStore {
    private val state = MutableStateFlow<Map<String, CallSummary>>(emptyMap())

    val saved: Map<String, CallSummary> get() = state.value

    override suspend fun save(eventId: EventId, summary: CallSummary) {
        state.value = state.value + (eventId.value to summary)
    }

    override fun observe(eventId: EventId): Flow<CallSummary?> =
        state.map { it[eventId.value] }
}
