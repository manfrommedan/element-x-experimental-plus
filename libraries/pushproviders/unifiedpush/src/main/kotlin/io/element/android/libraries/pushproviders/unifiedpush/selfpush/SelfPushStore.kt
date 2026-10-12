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
import io.element.android.libraries.core.extensions.runCatchingExceptions

/** Where a session's pushes are picked up from: the ntfy server and the topic on it. */
data class SelfPushRegistration(
    val server: String,
    val topic: String,
)

/** How the listener talks to the notification server, mirroring the ntfy app's own protocol choice. */
enum class SelfPushProtocol {
    WEBSOCKET,
    JSON_STREAM,
}

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

    /** Stream protocol, WebSocket unless the user picked otherwise. */
    fun getProtocol(): SelfPushProtocol
    fun setProtocol(protocol: SelfPushProtocol)

    /** Server timestamp of the last push seen on the topic, the base for catch-up replays. */
    fun getLastMessageTime(topic: String): Long?
    fun storeLastMessageTime(topic: String, time: Long)
    fun removeLastMessageTime(topic: String)
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
        val topic = sharedPreferences.getString(PREFS_TOPIC + clientSecret, null)
        sharedPreferences.edit {
            remove(PREFS_TOPIC + clientSecret)
            remove(PREFS_SERVER + clientSecret)
            if (topic != null) {
                remove(PREFS_LAST_MESSAGE_TIME + topic)
            }
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

    override fun getProtocol(): SelfPushProtocol {
        val stored = sharedPreferences.getString(PREFS_PROTOCOL, null) ?: return SelfPushProtocol.WEBSOCKET
        return runCatchingExceptions { SelfPushProtocol.valueOf(stored) }.getOrDefault(SelfPushProtocol.WEBSOCKET)
    }

    override fun setProtocol(protocol: SelfPushProtocol) {
        sharedPreferences.edit { putString(PREFS_PROTOCOL, protocol.name) }
    }

    override fun getLastMessageTime(topic: String): Long? {
        val key = PREFS_LAST_MESSAGE_TIME + topic
        return if (sharedPreferences.contains(key)) sharedPreferences.getLong(key, 0L) else null
    }

    override fun storeLastMessageTime(topic: String, time: Long) {
        val key = PREFS_LAST_MESSAGE_TIME + topic
        if (time > sharedPreferences.getLong(key, 0L)) {
            sharedPreferences.edit { putLong(key, time) }
        }
    }

    override fun removeLastMessageTime(topic: String) {
        sharedPreferences.edit { remove(PREFS_LAST_MESSAGE_TIME + topic) }
    }

    companion object {
        private const val PREFS_TOPIC = "SELF_PUSH_TOPIC_"
        private const val PREFS_SERVER = "SELF_PUSH_SERVER_"
        private const val PREFS_CUSTOM_SERVER = "SELF_PUSH_CUSTOM_SERVER"
        private const val PREFS_READ_TOKEN = "SELF_PUSH_READ_TOKEN"
        private const val PREFS_PROTOCOL = "SELF_PUSH_PROTOCOL"
        private const val PREFS_LAST_MESSAGE_TIME = "SELF_PUSH_LAST_MESSAGE_TIME_"
    }
}
