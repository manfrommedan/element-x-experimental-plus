/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.test

import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushAvailability
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushProtocol
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushRegistrar
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushRegistration
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushStore
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushStreamRefresher

class FakeSelfPushStore : SelfPushStore {
    private val registrations = mutableMapOf<String, SelfPushRegistration>()
    private val lastMessageTimes = mutableMapOf<String, Long>()
    private var customServer: String? = null
    private var readToken: String? = null
    private var protocol: SelfPushProtocol = SelfPushProtocol.WEBSOCKET

    override fun getRegistration(clientSecret: String): SelfPushRegistration? = registrations[clientSecret]

    override fun storeRegistration(clientSecret: String, registration: SelfPushRegistration) {
        registrations[clientSecret] = registration
    }

    override fun removeRegistration(clientSecret: String) {
        registrations.remove(clientSecret)?.let { lastMessageTimes.remove(it.topic) }
    }

    override fun getAllRegistrations(): Map<String, SelfPushRegistration> = registrations.toMap()

    override fun getCustomServer(): String? = customServer

    override fun setCustomServer(server: String?) {
        customServer = server
    }

    override fun getReadToken(): String? = readToken

    override fun setReadToken(token: String?) {
        readToken = token
    }

    override fun getProtocol(): SelfPushProtocol = protocol

    override fun setProtocol(protocol: SelfPushProtocol) {
        this.protocol = protocol
    }

    private val openServers = mutableSetOf<String>()

    override fun setStreamOpen(server: String, open: Boolean) {
        if (open) openServers += server else openServers -= server
    }

    override fun getOpenServers(): Set<String> = openServers.toSet()

    override fun getLastMessageTime(topic: String): Long? = lastMessageTimes[topic]

    override fun storeLastMessageTime(topic: String, time: Long) {
        if (time > lastMessageTimes[topic] ?: 0L) {
            lastMessageTimes[topic] = time
        }
    }

    override fun removeLastMessageTime(topic: String) {
        lastMessageTimes.remove(topic)
    }
}

class FakeSelfPushRegistrar(
    private val registerResult: () -> Result<Unit> = { Result.success(Unit) },
) : SelfPushRegistrar {
    val registeredSecrets = mutableListOf<String>()
    val unregisteredSecrets = mutableListOf<String>()

    override suspend fun register(clientSecret: String): Result<Unit> {
        registeredSecrets += clientSecret
        return registerResult()
    }

    override fun unregister(clientSecret: String) {
        unregisteredSecrets += clientSecret
    }
}

class FakeSelfPushStreamRefresher : SelfPushStreamRefresher {
    var refreshCount = 0
        private set

    override fun refresh() {
        refreshCount++
    }
}

class FakeSelfPushAvailability(
    private val enabled: Boolean,
) : SelfPushAvailability {
    override fun isEnabled(): Boolean = enabled
}
