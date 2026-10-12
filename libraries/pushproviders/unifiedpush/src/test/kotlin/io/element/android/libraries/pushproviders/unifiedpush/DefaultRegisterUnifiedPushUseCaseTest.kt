/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.test.AN_EXCEPTION
import io.element.android.libraries.matrix.test.A_SECRET
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.pushproviders.api.Distributor
import io.element.android.libraries.pushproviders.test.FakeSelfPushAvailability
import io.element.android.libraries.pushproviders.test.FakeSelfPushRegistrar
import io.element.android.libraries.pushproviders.test.FakeSelfPushStore
import io.element.android.libraries.pushproviders.unifiedpush.registration.EndpointRegistrationHandler
import io.element.android.libraries.pushproviders.unifiedpush.registration.RegistrationResult
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushAvailability
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushRegistrar
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushRegistration
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushStore
import io.element.android.tests.testutils.fake.FakeAndroidKeyStore
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class DefaultRegisterUnifiedPushUseCaseTest : RobolectricTest() {
    @Before
    fun setup() {
        FakeAndroidKeyStore.setup
    }

    @Test
    fun `test registration successful`() = runTest {
        val endpointRegistrationHandler = EndpointRegistrationHandler()
        val useCase = createDefaultRegisterUnifiedPushUseCase(
            endpointRegistrationHandler = endpointRegistrationHandler
        )
        val aDistributor = Distributor("aValue", "aName")
        launch {
            delay(100)
            endpointRegistrationHandler.registrationDone(RegistrationResult(A_SECRET, Result.success(Unit)))
        }
        val result = useCase.execute(aDistributor, A_SECRET, A_SESSION_ID)
        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `test registration error`() = runTest {
        val endpointRegistrationHandler = EndpointRegistrationHandler()
        val useCase = createDefaultRegisterUnifiedPushUseCase(
            endpointRegistrationHandler = endpointRegistrationHandler
        )
        val aDistributor = Distributor("aValue", "aName")
        launch {
            delay(100)
            endpointRegistrationHandler.registrationDone(RegistrationResult(A_SECRET, Result.failure(AN_EXCEPTION)))
        }
        val result = useCase.execute(aDistributor, A_SECRET, A_SESSION_ID)
        assertThat(result.isSuccess).isFalse()
    }

    @Test
    fun `test registration timeout`() = runTest {
        val endpointRegistrationHandler = EndpointRegistrationHandler()
        val useCase = createDefaultRegisterUnifiedPushUseCase(
            endpointRegistrationHandler = endpointRegistrationHandler
        )
        val aDistributor = Distributor("aValue", "aName")
        val result = useCase.execute(aDistributor, A_SECRET, A_SESSION_ID)
        assertThat(result.isSuccess).isFalse()
    }

    @Test
    fun `registration with the app itself as distributor goes to the built-in listener`() = runTest {
        val selfPushRegistrar = FakeSelfPushRegistrar()
        val context = InstrumentationRegistry.getInstrumentation().context
        val useCase = createDefaultRegisterUnifiedPushUseCase(
            selfPushAvailability = FakeSelfPushAvailability(enabled = true),
            selfPushRegistrar = selfPushRegistrar,
        )
        val result = useCase.execute(Distributor(context.packageName, "Built-in push"), A_SECRET, A_SESSION_ID)
        assertThat(result.isSuccess).isTrue()
        assertThat(selfPushRegistrar.registeredSecrets).containsExactly(A_SECRET)
    }

    @Test
    fun `registration with the app itself as distributor falls back to UnifiedPush when the flag is off`() = runTest {
        val endpointRegistrationHandler = EndpointRegistrationHandler()
        val selfPushRegistrar = FakeSelfPushRegistrar()
        val context = InstrumentationRegistry.getInstrumentation().context
        val useCase = createDefaultRegisterUnifiedPushUseCase(
            endpointRegistrationHandler = endpointRegistrationHandler,
            selfPushAvailability = FakeSelfPushAvailability(enabled = false),
            selfPushRegistrar = selfPushRegistrar,
        )
        launch {
            delay(100)
            endpointRegistrationHandler.registrationDone(RegistrationResult(A_SECRET, Result.success(Unit)))
        }
        val result = useCase.execute(Distributor(context.packageName, "Built-in push"), A_SECRET, A_SESSION_ID)
        assertThat(result.isSuccess).isTrue()
        assertThat(selfPushRegistrar.registeredSecrets).isEmpty()
    }

    @Test
    fun `registration with an external distributor retires a built-in registration on the same secret`() = runTest {
        val endpointRegistrationHandler = EndpointRegistrationHandler()
        val selfPushRegistrar = FakeSelfPushRegistrar()
        val selfPushStore = FakeSelfPushStore()
        selfPushStore.storeRegistration(A_SECRET, SelfPushRegistration(server = "https://ntfy.sh", topic = "up1234abcd"))
        val useCase = createDefaultRegisterUnifiedPushUseCase(
            endpointRegistrationHandler = endpointRegistrationHandler,
            selfPushRegistrar = selfPushRegistrar,
            selfPushStore = selfPushStore,
        )
        launch {
            delay(100)
            endpointRegistrationHandler.registrationDone(RegistrationResult(A_SECRET, Result.success(Unit)))
        }
        val result = useCase.execute(Distributor("aValue", "aName"), A_SECRET, A_SESSION_ID)
        assertThat(result.isSuccess).isTrue()
        assertThat(selfPushRegistrar.unregisteredSecrets).containsExactly(A_SECRET)
    }

    @Test
    fun `registration with an external distributor does not touch the listener when there is nothing to retire`() = runTest {
        val endpointRegistrationHandler = EndpointRegistrationHandler()
        val selfPushRegistrar = FakeSelfPushRegistrar()
        val useCase = createDefaultRegisterUnifiedPushUseCase(
            endpointRegistrationHandler = endpointRegistrationHandler,
            selfPushRegistrar = selfPushRegistrar,
        )
        launch {
            delay(100)
            endpointRegistrationHandler.registrationDone(RegistrationResult(A_SECRET, Result.success(Unit)))
        }
        val result = useCase.execute(Distributor("aValue", "aName"), A_SECRET, A_SESSION_ID)
        assertThat(result.isSuccess).isTrue()
        assertThat(selfPushRegistrar.unregisteredSecrets).isEmpty()
    }

    private fun TestScope.createDefaultRegisterUnifiedPushUseCase(
        endpointRegistrationHandler: EndpointRegistrationHandler = EndpointRegistrationHandler(),
        selfPushAvailability: SelfPushAvailability = FakeSelfPushAvailability(enabled = false),
        selfPushRegistrar: SelfPushRegistrar = FakeSelfPushRegistrar(),
        selfPushStore: SelfPushStore = FakeSelfPushStore(),
    ): DefaultRegisterUnifiedPushUseCase {
        val context = InstrumentationRegistry.getInstrumentation().context
        return DefaultRegisterUnifiedPushUseCase(
            context = context,
            endpointRegistrationHandler = endpointRegistrationHandler,
            selfPushAvailability = selfPushAvailability,
            selfPushRegistrar = selfPushRegistrar,
            selfPushStore = selfPushStore,
        )
    }
}
