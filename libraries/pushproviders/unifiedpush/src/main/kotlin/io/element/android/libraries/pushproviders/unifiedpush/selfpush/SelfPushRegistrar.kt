/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.selfpush

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.core.log.logger.LoggerTag
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.pushproviders.unifiedpush.UnifiedPushGatewayResolver
import io.element.android.libraries.pushproviders.unifiedpush.UnifiedPushGatewayUrlResolver
import io.element.android.libraries.pushproviders.unifiedpush.UnifiedPushNewGatewayHandler
import io.element.android.libraries.pushproviders.unifiedpush.UnifiedPushStore
import timber.log.Timber
import java.util.UUID

private val loggerTag = LoggerTag("SelfPushRegistrar", LoggerTag.PushLoggerTag)

/**
 * Registers a session for pushes with the app acting as its own distributor.
 *
 * Where an external distributor would come back with an endpoint through the UnifiedPush
 * IPC, here the endpoint is made up locally: a fresh random topic on the configured
 * notification server, served in the UnifiedPush format (`?up=1`). From there the flow is
 * the stock one — gateway discovery, pusher registration on the homeserver — and the
 * listener service picks the topic up.
 */
interface SelfPushRegistrar {
    suspend fun register(clientSecret: String): Result<Unit>
    fun unregister(clientSecret: String)
}

@ContributesBinding(AppScope::class)
class DefaultSelfPushRegistrar(
    @ApplicationContext private val context: Context,
    private val selfPushStore: SelfPushStore,
    private val unifiedPushStore: UnifiedPushStore,
    private val unifiedPushGatewayResolver: UnifiedPushGatewayResolver,
    private val unifiedPushGatewayUrlResolver: UnifiedPushGatewayUrlResolver,
    private val newGatewayHandler: UnifiedPushNewGatewayHandler,
) : SelfPushRegistrar {
    override suspend fun register(clientSecret: String): Result<Unit> {
        val server = (selfPushStore.getCustomServer() ?: DEFAULT_SERVER).trimEnd('/')
        // Re-registering an already registered session keeps its topic: the running stream
        // stays valid and the homeserver pusher does not churn. A fresh topic is only minted
        // for new sessions or when the server changed under the registration.
        val existing = selfPushStore.getRegistration(clientSecret)
        val topic = when {
            existing == null -> newTopic()
            existing.server == server -> existing.topic
            else -> {
                // The old topic's catch-up timestamp means nothing on the new server.
                selfPushStore.removeRegistration(clientSecret)
                newTopic()
            }
        }
        val endpoint = "$server/$topic?up=1"

        val gateway = unifiedPushGatewayResolver.getGateway(endpoint)
            .let { gatewayResult -> unifiedPushGatewayUrlResolver.resolve(gatewayResult, clientSecret) }
        unifiedPushStore.storePushGateway(clientSecret, gateway)

        return newGatewayHandler.handle(endpoint, gateway, clientSecret)
            .onSuccess {
                unifiedPushStore.storeUpEndpoint(clientSecret, endpoint)
                selfPushStore.storeRegistration(clientSecret, SelfPushRegistration(server = server, topic = topic))
                Timber.tag(loggerTag.value).d("Registered the built-in push listener for a session")
                SelfPushSubscriberService.start(context)
            }
            .onFailure {
                Timber.tag(loggerTag.value).w(it, "Failed to register the pusher")
            }
    }

    override fun unregister(clientSecret: String) {
        selfPushStore.removeRegistration(clientSecret)
        // Refresh the streams: the dropped topic leaves, and the service stops itself
        // when nothing is left to listen to.
        SelfPushSubscriberService.start(context)
    }

    private fun newTopic(): String {
        return "up" + UUID.randomUUID().toString().replace("-", "")
    }

    private companion object {
        const val DEFAULT_SERVER = "https://ntfy.sh"
    }
}
