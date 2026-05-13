/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.call.api

import io.element.android.libraries.matrix.api.core.EventId
import kotlinx.coroutines.flow.Flow

interface CallSummaryStore {
    suspend fun save(eventId: EventId, summary: CallSummary)
    fun observe(eventId: EventId): Flow<CallSummary?>
}
