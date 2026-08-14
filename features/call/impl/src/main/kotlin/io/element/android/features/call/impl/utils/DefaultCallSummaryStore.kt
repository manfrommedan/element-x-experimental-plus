/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.utils

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.features.call.api.CallSummary
import io.element.android.features.call.api.CallSummaryStore
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.core.EventId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import timber.log.Timber

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultCallSummaryStore(
    @ApplicationContext context: Context,
    @AppCoroutineScope scope: CoroutineScope,
) : CallSummaryStore {
    private val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
        context.preferencesDataStoreFile("call_summaries")
    }

    override suspend fun save(eventId: EventId, summary: CallSummary) {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(eventId.value)] = CallSummaryCodec.encode(summary)
        }
    }

    override fun observe(eventId: EventId): Flow<CallSummary?> {
        val key = stringPreferencesKey(eventId.value)
        return dataStore.data
            .map { prefs -> prefs[key]?.let(CallSummaryCodec::decode) }
            .distinctUntilChanged()
    }
}

internal object CallSummaryCodec {
    private const val NO_ANSWER = "noanswer"
    private const val CONNECTED_PREFIX = "connected:"

    fun encode(summary: CallSummary): String = when (summary) {
        is CallSummary.NoAnswer -> NO_ANSWER
        is CallSummary.Connected -> "$CONNECTED_PREFIX${summary.durationSeconds}"
    }

    fun decode(value: String): CallSummary? = when {
        value == NO_ANSWER -> CallSummary.NoAnswer
        value.startsWith(CONNECTED_PREFIX) -> {
            val seconds = value.removePrefix(CONNECTED_PREFIX).toLongOrNull()
            if (seconds == null) {
                Timber.w("Discarding unparseable connected call summary: '$value'")
                null
            } else {
                CallSummary.Connected(seconds)
            }
        }
        else -> {
            Timber.w("Discarding unknown call summary payload: '$value'")
            null
        }
    }
}
