/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.notifications.selfpush

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.test.AN_EXCEPTION
import io.element.android.libraries.matrix.test.A_SECRET
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.core.aBuildMeta
import io.element.android.libraries.push.impl.battery.BatteryOptimization
import io.element.android.libraries.push.test.FakePushService
import io.element.android.libraries.pushproviders.api.Distributor
import io.element.android.libraries.pushproviders.api.PushProvider
import io.element.android.libraries.pushproviders.test.FakePushProvider
import io.element.android.libraries.pushproviders.test.FakeSelfPushStore
import io.element.android.libraries.pushproviders.test.FakeSelfPushStreamRefresher
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.DEFAULT_SELF_PUSH_SERVER
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushProtocol
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushRegistration
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushStore
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushStreamRefresher
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushStreamStates
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushStreamStatus
import io.element.android.tests.testutils.consumeItemsUntilPredicate
import io.element.android.tests.testutils.lambda.any
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.lambda.value
import io.element.android.tests.testutils.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test

class SelfPushSettingsPresenterTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `present - initial state`() = runTest {
        val presenter = createSelfPushSettingsPresenter()
        presenter.test {
            val state = awaitItem()
            assertThat(state.isActive).isFalse()
            assertThat(state.listenerStatus).isEqualTo(SelfPushSettingsState.ListenerStatus.INACTIVE)
            assertThat(state.server.effectiveServer).isEqualTo(DEFAULT_SELF_PUSH_SERVER)
            assertThat(state.protocol.protocol).isEqualTo(SelfPushProtocol.WEBSOCKET)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `present - saving a server persists it and re-registers the built-in distributor`() = runTest {
        val registerRecorder = lambdaRecorder<MatrixClient, PushProvider, Distributor, Result<Unit>> { _, _, _ ->
            Result.success(Unit)
        }
        val selfPushStore = FakeSelfPushStore()
        val presenter = createSelfPushSettingsPresenter(
            pushService = createBuiltInPushService(registerWithLambda = { client, provider, distributor ->
                registerRecorder(client, provider, distributor)
            }),
            selfPushStore = selfPushStore,
        )
        presenter.test {
            val initial = consumeItemsUntilPredicate { true }.last()
            initial.eventSink(SelfPushSettingsEvent.ShowServerDialog)
            val shown = consumeItemsUntilPredicate { it.server.showDialog }.last()
            shown.eventSink(SelfPushSettingsEvent.SaveServer("https://push.example.com", "tk_read_1"))
            val saved = consumeItemsUntilPredicate {
                it.server.customServer == "https://push.example.com" && !it.server.showDialog
            }.last()
            advanceUntilIdle()
            assertThat(selfPushStore.getCustomServer()).isEqualTo("https://push.example.com")
            assertThat(selfPushStore.getReadToken()).isEqualTo("tk_read_1")
            assertThat(saved.server.effectiveServer).isEqualTo("https://push.example.com")
            assertThat(saved.server.saveFailed).isFalse()
            registerRecorder.assertions().isCalledOnce().with(
                any(),
                any(),
                value(Distributor(value = SELF_APPLICATION_ID, name = "Built-in push")),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `present - saving an invalid server URL does not persist it`() = runTest {
        val registerRecorder = lambdaRecorder<MatrixClient, PushProvider, Distributor, Result<Unit>> { _, _, _ ->
            Result.success(Unit)
        }
        val selfPushStore = FakeSelfPushStore()
        val presenter = createSelfPushSettingsPresenter(
            pushService = createBuiltInPushService(registerWithLambda = { client, provider, distributor ->
                registerRecorder(client, provider, distributor)
            }),
            selfPushStore = selfPushStore,
        )
        presenter.test {
            val initial = consumeItemsUntilPredicate { true }.last()
            initial.eventSink(SelfPushSettingsEvent.ShowServerDialog)
            val shown = consumeItemsUntilPredicate { it.server.showDialog }.last()
            shown.eventSink(SelfPushSettingsEvent.SaveServer("http://push.example.com/", null))
            val failed = consumeItemsUntilPredicate { it.server.saveFailed }.last()
            advanceUntilIdle()
            assertThat(selfPushStore.getCustomServer()).isNull()
            // The dialog stays open so the user can fix the URL.
            assertThat(failed.server.showDialog).isTrue()
            registerRecorder.assertions().isNeverCalled()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `present - failing re-registration after a server change rolls the store back`() = runTest {
        val selfPushStore = FakeSelfPushStore()
        val presenter = createSelfPushSettingsPresenter(
            pushService = createBuiltInPushService(registerWithLambda = { _, _, _ ->
                Result.failure(AN_EXCEPTION)
            }),
            selfPushStore = selfPushStore,
        )
        presenter.test {
            val initial = consumeItemsUntilPredicate { true }.last()
            initial.eventSink(SelfPushSettingsEvent.ShowServerDialog)
            val shown = consumeItemsUntilPredicate { it.server.showDialog }.last()
            shown.eventSink(SelfPushSettingsEvent.SaveServer("https://push.example.com", "tk_new"))
            consumeItemsUntilPredicate { it.server.saveFailed }.last()
            advanceUntilIdle()
            // The store never advertises a server the live registration does not use.
            assertThat(selfPushStore.getCustomServer()).isNull()
            assertThat(selfPushStore.getReadToken()).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `present - resetting the server clears it and re-registers`() = runTest {
        val registerRecorder = lambdaRecorder<MatrixClient, PushProvider, Distributor, Result<Unit>> { _, _, _ ->
            Result.success(Unit)
        }
        val selfPushStore = FakeSelfPushStore()
        selfPushStore.setCustomServer("https://push.example.com")
        selfPushStore.setReadToken("tk_read_1")
        val presenter = createSelfPushSettingsPresenter(
            pushService = createBuiltInPushService(registerWithLambda = { client, provider, distributor ->
                registerRecorder(client, provider, distributor)
            }),
            selfPushStore = selfPushStore,
        )
        presenter.test {
            val initial = consumeItemsUntilPredicate {
                it.server.customServer == "https://push.example.com"
            }.last()
            initial.eventSink(SelfPushSettingsEvent.ResetServer)
            val reset = consumeItemsUntilPredicate { it.server.customServer == null }.last()
            advanceUntilIdle()
            assertThat(selfPushStore.getCustomServer()).isNull()
            assertThat(selfPushStore.getReadToken()).isNull()
            assertThat(reset.server.effectiveServer).isEqualTo(DEFAULT_SELF_PUSH_SERVER)
            registerRecorder.assertions().isCalledOnce()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `present - protocol picker persists the choice and refreshes the streams`() = runTest {
        val selfPushStore = FakeSelfPushStore()
        val refresher = FakeSelfPushStreamRefresher()
        val presenter = createSelfPushSettingsPresenter(
            selfPushStore = selfPushStore,
            selfPushStreamRefresher = refresher,
        )
        presenter.test {
            val initial = consumeItemsUntilPredicate { true }.last()
            assertThat(initial.protocol.protocol).isEqualTo(SelfPushProtocol.WEBSOCKET)
            initial.eventSink(SelfPushSettingsEvent.ShowProtocolDialog)
            val shown = consumeItemsUntilPredicate { it.protocol.showDialog }.last()
            shown.eventSink(SelfPushSettingsEvent.SetProtocol(SelfPushProtocol.JSON_STREAM))
            val saved = consumeItemsUntilPredicate {
                it.protocol.protocol == SelfPushProtocol.JSON_STREAM && !it.protocol.showDialog
            }.last()
            advanceUntilIdle()
            assertThat(selfPushStore.getProtocol()).isEqualTo(SelfPushProtocol.JSON_STREAM)
            assertThat(saved.protocol.protocol).isEqualTo(SelfPushProtocol.JSON_STREAM)
            assertThat(refresher.refreshCount).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `present - battery row reflects the system setting and forwards the exemption request`() = runTest {
        val batteryOptimization = FakeBatteryOptimization()
        val presenter = createSelfPushSettingsPresenter(
            batteryOptimization = batteryOptimization,
        )
        presenter.test {
            val initial = consumeItemsUntilPredicate { true }.last()
            assertThat(initial.batteryOptimizationIgnored).isFalse()
            initial.eventSink(SelfPushSettingsEvent.RequestDisableBatteryOptimization)
            advanceUntilIdle()
            assertThat(batteryOptimization.requestCount).isEqualTo(1)
            // The system granted the exemption while the dialog was up; a resume refresh reflects it.
            batteryOptimization.ignoring = true
            initial.eventSink(SelfPushSettingsEvent.RefreshStatus)
            val refreshed = consumeItemsUntilPredicate { it.batteryOptimizationIgnored }.last()
            assertThat(refreshed.batteryOptimizationIgnored).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `present - listener status falls back to the persisted open flags`() = runTest {
        val selfPushStore = FakeSelfPushStore()
        selfPushStore.storeRegistration(A_SECRET, SelfPushRegistration("https://ntfy.sh", "upA"))
        val presenter = createSelfPushSettingsPresenter(
            selfPushStore = selfPushStore,
        )
        presenter.test {
            // The first emissions are INACTIVE until the provider lookup flips isActive.
            val connecting = consumeItemsUntilPredicate {
                it.listenerStatus == SelfPushSettingsState.ListenerStatus.CONNECTING
            }.last()

            selfPushStore.setStreamOpen("https://ntfy.sh", true)
            connecting.eventSink(SelfPushSettingsEvent.RefreshStatus)

            val connected = consumeItemsUntilPredicate {
                it.listenerStatus == SelfPushSettingsState.ListenerStatus.CONNECTED
            }.last()
            assertThat(connected.listenerStatus).isEqualTo(SelfPushSettingsState.ListenerStatus.CONNECTED)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `present - listener status follows the live stream states without a refresh`() = runTest {
        val selfPushStore = FakeSelfPushStore()
        selfPushStore.storeRegistration(A_SECRET, SelfPushRegistration("https://ntfy.sh", "upA"))
        selfPushStore.setStreamOpen("https://ntfy.sh", true)
        val streamStates = SelfPushStreamStates()
        val presenter = createSelfPushSettingsPresenter(
            selfPushStore = selfPushStore,
            selfPushStreamStates = streamStates,
        )
        presenter.test {
            // isActive flips true once the provider lookup lands; wait for the connected row.
            val connected = consumeItemsUntilPredicate {
                it.listenerStatus == SelfPushSettingsState.ListenerStatus.CONNECTED
            }.last()

            streamStates.set("https://ntfy.sh", SelfPushStreamStatus.RECONNECTING)
            val reconnecting = consumeItemsUntilPredicate {
                it.listenerStatus == SelfPushSettingsState.ListenerStatus.RECONNECTING
            }.last()
            assertThat(reconnecting.listenerStatus).isEqualTo(SelfPushSettingsState.ListenerStatus.RECONNECTING)

            streamStates.set("https://ntfy.sh", SelfPushStreamStatus.REFUSED)
            val refused = consumeItemsUntilPredicate {
                it.listenerStatus == SelfPushSettingsState.ListenerStatus.REFUSED
            }.last()
            assertThat(refused.listenerStatus).isEqualTo(SelfPushSettingsState.ListenerStatus.REFUSED)

            connected.eventSink(SelfPushSettingsEvent.RefreshStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun createBuiltInPushService(
        registerWithLambda: (MatrixClient, PushProvider, Distributor) -> Result<Unit> = { _, _, _ ->
            Result.success(Unit)
        }
    ): FakePushService {
        val selfDistributor = Distributor(value = SELF_APPLICATION_ID, name = "Built-in push")
        val pushProvider = FakePushProvider(
            index = 0,
            name = "aFakePushProvider",
            distributors = listOf(selfDistributor),
        )
        return FakePushService(
            availablePushProviders = listOf(pushProvider),
            registerWithLambda = registerWithLambda,
        )
    }

    private fun TestScope.createSelfPushSettingsPresenter(
        pushService: FakePushService = createBuiltInPushService(),
        selfPushStore: SelfPushStore = FakeSelfPushStore(),
        selfPushStreamRefresher: SelfPushStreamRefresher = FakeSelfPushStreamRefresher(),
        selfPushStreamStates: SelfPushStreamStates = SelfPushStreamStates(),
        batteryOptimization: BatteryOptimization = FakeBatteryOptimization(),
        buildMeta: BuildMeta = aBuildMeta(applicationId = SELF_APPLICATION_ID),
    ): SelfPushSettingsPresenter {
        return SelfPushSettingsPresenter(
            matrixClient = FakeMatrixClient(),
            pushService = pushService,
            selfPushStore = selfPushStore,
            selfPushStreamRefresher = selfPushStreamRefresher,
            selfPushStreamStates = selfPushStreamStates,
            batteryOptimization = batteryOptimization,
            buildMeta = buildMeta,
            okHttpClient = OkHttpClient(),
        )
    }

    private class FakeBatteryOptimization : BatteryOptimization {
        var ignoring = false
        var requestCount = 0
            private set

        override fun isIgnoringBatteryOptimizations(): Boolean = ignoring

        override fun requestDisablingBatteryOptimization(): Boolean {
            requestCount++
            return true
        }
    }

    companion object {
        private const val SELF_APPLICATION_ID = "app.self"
    }
}
