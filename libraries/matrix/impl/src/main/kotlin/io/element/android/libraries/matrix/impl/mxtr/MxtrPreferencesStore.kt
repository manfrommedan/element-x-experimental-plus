/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.mxtr

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private val KEY_ENABLED = booleanPreferencesKey("enabled")
private val KEY_SHARE = stringPreferencesKey("share_string")

class MxtrPreferencesStore(private val context: Context) {
    fun enabledFlow(): Flow<Boolean> = dataStore(context).data.map {
        // Enabled requires both the switch and a valid share-string. Treat
        // absent/invalid share-string as disabled even if KEY_ENABLED is true,
        // so a half-configured state never flips the proxy on.
        configFrom(it).enabled
    }

    fun shareStringFlow(): Flow<String> = dataStore(context).data.map {
        it[KEY_SHARE].orEmpty()
    }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore(context).edit { it[KEY_ENABLED] = enabled }
    }

    suspend fun setShareString(value: String) {
        dataStore(context).edit { it[KEY_SHARE] = value }
    }

    suspend fun clearShareString() {
        dataStore(context).edit { it.remove(KEY_SHARE) }
    }

    /**
     * Synchronous snapshot. Returns the cached value populated by
     * [snapshotCache] when available (no blocking, no ANR risk on UI thread),
     * otherwise does a one-time blocking read and seeds the cache so future
     * calls are cheap. Subsequent settings edits keep the cache in sync via
     * the long-lived collector started by [Companion.startCacheCollector].
     */
    fun snapshotBlocking(): MxtrRuntimeConfig {
        snapshotCache.get()?.let { return it }
        val cfg = runBlocking { readOnce() }
        snapshotCache.compareAndSet(null, cfg)
        return cfg
    }

    private suspend fun readOnce(): MxtrRuntimeConfig {
        return configFrom(dataStore(context).data.first())
    }

    companion object {
        private const val TAG = "MxtrPrefsStore"

        // Process-wide DataStore singleton for "mxtr_prefs". It must be a single
        // instance per process because many short-lived MxtrPreferencesStore
        // instances share the same file; creating it exactly once here keeps that
        // guarantee (the previous `by preferencesDataStore` delegate did the same).
        @Volatile
        private var dataStoreInstance: DataStore<Preferences>? = null

        private fun dataStore(context: Context): DataStore<Preferences> =
            dataStoreInstance ?: synchronized(this) {
                dataStoreInstance ?: PreferenceDataStoreFactory.create {
                    context.applicationContext.preferencesDataStoreFile("mxtr_prefs")
                }.also { dataStoreInstance = it }
            }

        // Single shared cache populated at process start by startCacheCollector
        // and kept fresh by the same collector. Reads from snapshotBlocking()
        // never touch DataStore once the first read completes.
        private val snapshotCache = AtomicReference<MxtrRuntimeConfig?>(null)

        // CR3-01: idempotency guard so multi-process Application onCreate (or
        // test harnesses) can't spawn N forever-collectors that compete on the
        // same AtomicReference.
        private val collectorStarted = AtomicBoolean(false)

        // Process-wide collector scope. SupervisorJob so a transient DataStore
        // exception in our collector never crashes other coroutines. IO
        // dispatcher because DataStore work is IO-bound.
        private val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Start the long-lived collector that keeps [snapshotCache] in sync
         * with the on-disk DataStore. Call once from Application.onCreate
         * BEFORE any UI runs, so [snapshotBlocking] never has to do its first
         * cold read on the main thread.
         *
         * Synchronously seeds the cache with one blocking read so the very
         * next snapshotBlocking() call from MxtrHttpProxy.start or
         * MxtrWebViewProxy.applyGlobally (which run a few lines later in
         * Application.onCreate) never falls through to its runBlocking path
         * (ME3-08). The blocking read happens once per process, on the main
         * thread, before any UI is composed — Strict-mode-clean.
         */
        fun startCacheCollector(appContext: Context) {
            if (!collectorStarted.compareAndSet(false, true)) return
            val store = MxtrPreferencesStore(appContext.applicationContext)
            // WR4-03: launch the warmup off the main thread. The collector
            // emits the first value within milliseconds on a normal device.
            // Callers that hit snapshotBlocking() before the first emission
            // still fall through to the runBlocking path in snapshotBlocking
            // (bounded single-shot read), but Application.onCreate stays
            // Strict-mode clean.
            collectorScope.launch {
                dataStore(store.context).data
                    .map { configFrom(it) }
                    .retryWhen { cause, _ ->
                        // ME3-03: log + back off + retry instead of letting
                        // one IOException kill the cache forever.
                        Timber.tag(TAG).w(cause, "DataStore collector failed; retrying in 5s")
                        delay(5_000)
                        true
                    }
                    .onEach { snapshotCache.set(it) }
                    .catch { Timber.tag(TAG).e(it, "DataStore collector terminated") }
                    .collect {}
            }
        }

        private fun configFrom(prefs: Preferences): MxtrRuntimeConfig {
            val share = prefs[KEY_SHARE]
            val data = share?.let { MxtrShareString.parse(it) }
            // MxtrCrypto uses Cipher.getInstance("ChaCha20-Poly1305") which is
            // API 28+. On older Android (FOSS minSdk=24 means 7.0/7.1/8.x are
            // in scope) the first encrypt would crash, so force the proxy off
            // there. The settings screen still reads/writes the share-string
            // and switch, but DefaultProxyProvider, MxtrHttpProxy and the
            // CCT-aware launcher all gate on .enabled and become no-ops.
            val apiOk = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P
            val switchOn = prefs[KEY_ENABLED] ?: false
            val on = apiOk && switchOn && data != null
            return MxtrRuntimeConfig(enabled = on, data = data)
        }
    }
}

// data is null when the user has not pasted a share-string yet. Callers that
// gate on `enabled` should treat that as "no config; behave like upstream".
data class MxtrRuntimeConfig(
    val enabled: Boolean,
    val data: MxtrShareData?,
)
