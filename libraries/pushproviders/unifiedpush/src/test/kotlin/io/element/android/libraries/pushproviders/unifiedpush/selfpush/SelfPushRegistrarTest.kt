/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.selfpush

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.test.AN_EXCEPTION
import io.element.android.libraries.matrix.test.A_SECRET
import io.element.android.libraries.pushproviders.unifiedpush.FakeUnifiedPushStore
import io.element.android.libraries.pushproviders.unifiedpush.UnifiedPushGatewayResolver
import io.element.android.libraries.pushproviders.unifiedpush.UnifiedPushGatewayResolverResult
import io.element.android.libraries.pushproviders.unifiedpush.UnifiedPushGatewayUrlResolver
import io.element.android.libraries.pushproviders.unifiedpush.UnifiedPushNewGatewayHandler
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SelfPushRegistrarTest : RobolectricTest() {
    @Test
    fun `register stores the endpoint and the topic and registers the pusher`() = runTest {
        val selfPushStore = FakeSelfPushStore()
        val unifiedPushStore = defaultUnifiedPushStore()
        var registeredEndpoint: String? = null
        val registrar = createRegistrar(
            selfPushStore = selfPushStore,
            unifiedPushStore = unifiedPushStore,
            newGatewayHandler = object : UnifiedPushNewGatewayHandler {
                override suspend fun handle(endpoint: String, pushGateway: String, clientSecret: String): Result<Unit> {
                    registeredEndpoint = endpoint
                    return Result.success(Unit)
                }
            },
        )

        val result = registrar.register(A_SECRET)

        assertThat(result.isSuccess).isTrue()
        // UP-shaped endpoint on the default server, with a fresh topic
        assertThat(registeredEndpoint).matches("""https://ntfy\.sh/up[0-9a-f]{16}\?up=1""")
        assertThat(unifiedPushStore.getEndpoint(A_SECRET)).isEqualTo(registeredEndpoint)
        val registration = selfPushStore.getRegistration(A_SECRET)
        assertThat(registration).isNotNull()
        assertThat("""${registration!!.server}/${registration.topic}?up=1""").isEqualTo(registeredEndpoint)
    }

    @Test
    fun `register uses the custom server when one is set`() = runTest {
        val selfPushStore = FakeSelfPushStore().apply { setCustomServer("https://ntfy.example.org/") }
        var registeredEndpoint: String? = null
        val registrar = createRegistrar(
            selfPushStore = selfPushStore,
            newGatewayHandler = object : UnifiedPushNewGatewayHandler {
                override suspend fun handle(endpoint: String, pushGateway: String, clientSecret: String): Result<Unit> {
                    registeredEndpoint = endpoint
                    return Result.success(Unit)
                }
            },
        )

        registrar.register(A_SECRET)

        assertThat(registeredEndpoint).startsWith("https://ntfy.example.org/up")
    }

    @Test
    fun `register failure stores nothing`() = runTest {
        val selfPushStore = FakeSelfPushStore()
        val unifiedPushStore = defaultUnifiedPushStore()
        val registrar = createRegistrar(
            selfPushStore = selfPushStore,
            unifiedPushStore = unifiedPushStore,
            newGatewayHandler = object : UnifiedPushNewGatewayHandler {
                override suspend fun handle(endpoint: String, pushGateway: String, clientSecret: String): Result<Unit> {
                    return Result.failure(AN_EXCEPTION)
                }
            },
        )

        val result = registrar.register(A_SECRET)

        assertThat(result.isFailure).isTrue()
        assertThat(selfPushStore.getRegistration(A_SECRET)).isNull()
        assertThat(unifiedPushStore.getEndpoint(A_SECRET)).isNull()
    }

    @Test
    fun `unregister drops the registration`() = runTest {
        val selfPushStore = FakeSelfPushStore().apply {
            storeRegistration(A_SECRET, SelfPushRegistration("https://ntfy.sh", "up1234"))
        }
        val registrar = createRegistrar(selfPushStore = selfPushStore)

        registrar.unregister(A_SECRET)

        assertThat(selfPushStore.getRegistration(A_SECRET)).isNull()
    }

    private fun createRegistrar(
        selfPushStore: SelfPushStore = FakeSelfPushStore(),
        unifiedPushStore: FakeUnifiedPushStore = defaultUnifiedPushStore(),
        newGatewayHandler: UnifiedPushNewGatewayHandler = object : UnifiedPushNewGatewayHandler {
            override suspend fun handle(endpoint: String, pushGateway: String, clientSecret: String): Result<Unit> = Result.success(Unit)
        },
    ): DefaultSelfPushRegistrar {
        val context = InstrumentationRegistry.getInstrumentation().context
        return DefaultSelfPushRegistrar(
            context = context,
            selfPushStore = selfPushStore,
            unifiedPushStore = unifiedPushStore,
            unifiedPushGatewayResolver = object : UnifiedPushGatewayResolver {
                override suspend fun getGateway(endpoint: String) = UnifiedPushGatewayResolverResult.Success(
                    "https://gateway.example.org/_matrix/push/v1/notify"
                )
            },
            unifiedPushGatewayUrlResolver = object : UnifiedPushGatewayUrlResolver {
                override fun resolve(gatewayResult: UnifiedPushGatewayResolverResult, instance: String): String {
                    return (gatewayResult as UnifiedPushGatewayResolverResult.Success).gateway
                }
            },
            newGatewayHandler = newGatewayHandler,
        )
    }

    private fun defaultUnifiedPushStore(): FakeUnifiedPushStore {
        val endpoints = mutableMapOf<String, String?>()
        return FakeUnifiedPushStore(
            getEndpointResult = { endpoints[it] },
            storeUpEndpointResult = { secret, endpoint -> endpoints[secret] = endpoint },
            storePushGatewayResult = { _, _ -> },
        )
    }
}
