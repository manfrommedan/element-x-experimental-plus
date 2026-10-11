/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.selfpush

class FakeSelfPushStore : SelfPushStore {
    private val registrations = mutableMapOf<String, SelfPushRegistration>()
    private var customServer: String? = null
    private var readToken: String? = null

    override fun getRegistration(clientSecret: String): SelfPushRegistration? = registrations[clientSecret]

    override fun storeRegistration(clientSecret: String, registration: SelfPushRegistration) {
        registrations[clientSecret] = registration
    }

    override fun removeRegistration(clientSecret: String) {
        registrations.remove(clientSecret)
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

class FakeSelfPushAvailability(
    private val enabled: Boolean,
) : SelfPushAvailability {
    override fun isEnabled(): Boolean = enabled
}
