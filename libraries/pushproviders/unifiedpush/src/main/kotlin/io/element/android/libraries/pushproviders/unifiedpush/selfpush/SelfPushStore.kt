/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.selfpush

import android.content.SharedPreferences
import androidx.core.content.edit
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding

/** Where a session's pushes are picked up from: the ntfy server and the topic on it. */
data class SelfPushRegistration(
    val server: String,
    val topic: String,
)

interface SelfPushStore {
    fun getRegistration(clientSecret: String): SelfPushRegistration?
    fun storeRegistration(clientSecret: String, registration: SelfPushRegistration)
    fun removeRegistration(clientSecret: String)

    /** All live registrations, keyed by client secret. */
    fun getAllRegistrations(): Map<String, SelfPushRegistration>

    /** User-provided notification server overriding the default, null when untouched. */
    fun getCustomServer(): String?
    fun setCustomServer(server: String?)

    /** Bearer token the subscriber reads the stream with, null for open servers. */
    fun getReadToken(): String?
    fun setReadToken(token: String?)
}

@ContributesBinding(AppScope::class)
class SharedPreferencesSelfPushStore(
    private val sharedPreferences: SharedPreferences,
) : SelfPushStore {
    override fun getRegistration(clientSecret: String): SelfPushRegistration? {
        val topic = sharedPreferences.getString(PREFS_TOPIC + clientSecret, null) ?: return null
        val server = sharedPreferences.getString(PREFS_SERVER + clientSecret, null) ?: return null
        return SelfPushRegistration(server = server, topic = topic)
    }

    override fun storeRegistration(clientSecret: String, registration: SelfPushRegistration) {
        sharedPreferences.edit {
            putString(PREFS_TOPIC + clientSecret, registration.topic)
            putString(PREFS_SERVER + clientSecret, registration.server)
        }
    }

    override fun removeRegistration(clientSecret: String) {
        sharedPreferences.edit {
            remove(PREFS_TOPIC + clientSecret)
            remove(PREFS_SERVER + clientSecret)
        }
    }

    override fun getAllRegistrations(): Map<String, SelfPushRegistration> {
        return sharedPreferences.all.keys
            .filter { it.startsWith(PREFS_TOPIC) }
            .mapNotNull { key ->
                val clientSecret = key.removePrefix(PREFS_TOPIC)
                getRegistration(clientSecret)?.let { clientSecret to it }
            }
            .toMap()
    }

    override fun getCustomServer(): String? {
        return sharedPreferences.getString(PREFS_CUSTOM_SERVER, null)
    }

    override fun setCustomServer(server: String?) {
        sharedPreferences.edit { putString(PREFS_CUSTOM_SERVER, server) }
    }

    override fun getReadToken(): String? {
        return sharedPreferences.getString(PREFS_READ_TOKEN, null)
    }

    override fun setReadToken(token: String?) {
        sharedPreferences.edit { putString(PREFS_READ_TOKEN, token) }
    }

    companion object {
        private const val PREFS_TOPIC = "SELF_PUSH_TOPIC_"
        private const val PREFS_SERVER = "SELF_PUSH_SERVER_"
        private const val PREFS_CUSTOM_SERVER = "SELF_PUSH_CUSTOM_SERVER"
        private const val PREFS_READ_TOKEN = "SELF_PUSH_READ_TOKEN"
    }
}
