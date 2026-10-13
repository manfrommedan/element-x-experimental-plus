/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.notifications.selfpush

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Inject
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.push.api.PushService
import io.element.android.libraries.push.impl.battery.BatteryOptimization
import io.element.android.libraries.pushproviders.api.Distributor
import io.element.android.libraries.pushproviders.api.PushProvider
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.DEFAULT_SELF_PUSH_SERVER
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushStore
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushStreamRefresher
import io.element.android.libraries.pushproviders.unifiedpush.selfpush.SelfPushStreamStates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException

@Inject
class SelfPushSettingsPresenter(
    private val matrixClient: MatrixClient,
    private val pushService: PushService,
    private val selfPushStore: SelfPushStore,
    private val selfPushStreamRefresher: SelfPushStreamRefresher,
    private val selfPushStreamStates: SelfPushStreamStates,
    private val batteryOptimization: BatteryOptimization,
    private val buildMeta: BuildMeta,
    private val okHttpClient: OkHttpClient,
) : Presenter<SelfPushSettingsState> {
    // Serializes server/token save + re-register + rollback; two rapid changes must not
    // interleave or the loser's rollback would clobber the winner's server.
    private val serverLock = Mutex()

    @Composable
    override fun present(): SelfPushSettingsState {
        val localCoroutineScope = rememberCoroutineScope()

        var isActive by remember { mutableStateOf(false) }
        var statusRefresh by remember { mutableIntStateOf(0) }
        var refreshDistributor by remember { mutableIntStateOf(0) }

        LaunchedEffect(refreshDistributor) {
            val provider = pushService.getCurrentPushProvider(matrixClient.sessionId)
            isActive = provider?.getCurrentDistributor(matrixClient.sessionId)?.value == buildMeta.applicationId
        }

        var showServerDialog by remember { mutableStateOf(false) }
        var serverRefresh by remember { mutableIntStateOf(0) }
        var healthCheck by remember { mutableStateOf<AsyncData<Unit>>(AsyncData.Uninitialized) }
        var healthCheckServer by remember { mutableStateOf<String?>(null) }
        var healthCheckToken by remember { mutableStateOf<String?>(null) }
        var saveFailed by remember { mutableStateOf(false) }

        var showProtocolDialog by remember { mutableStateOf(false) }
        var protocolRefresh by remember { mutableIntStateOf(0) }

        // Live per-server stream health, written by the subscriber service; drives the status row.
        val liveStreamStatuses by selfPushStreamStates.states.collectAsState()

        // Read eagerly and again on resume: the user changes it in the system settings.
        var batteryOptimizationIgnored by remember { mutableStateOf(batteryOptimization.isIgnoringBatteryOptimizations()) }

        fun computeListenerStatus(): SelfPushSettingsState.ListenerStatus {
            return selfPushListenerStatus(
                active = isActive,
                registrations = selfPushStore.getAllRegistrations(),
                openServers = selfPushStore.getOpenServers(),
                liveStatuses = liveStreamStatuses,
            )
        }

        fun selfDistributor(): Pair<PushProvider, Distributor>? {
            return pushService.getAvailablePushProviders()
                .flatMap { provider -> provider.getDistributors().map { provider to it } }
                .firstOrNull { it.second.value == buildMeta.applicationId }
        }

        // Applies the push server change and re-registers the built-in distributor so the
        // homeserver pusher and the local subscriber agree on the new server. The store write,
        // the registration and the rollback all happen under the lock, and on failure the store
        // is rolled back so it never advertises a server the live registration does not use.
        fun CoroutineScope.applyServer(newServer: String?, newReadToken: String?) = launch {
            serverLock.withLock {
                val previousServer = selfPushStore.getCustomServer()
                val previousToken = selfPushStore.getReadToken()
                selfPushStore.setCustomServer(newServer)
                selfPushStore.setReadToken(newReadToken)
                serverRefresh++

                val selfRegistration = selfDistributor()
                if (selfRegistration == null) {
                    Timber.w("Built-in distributor not found, cannot re-register after push server change")
                    saveFailed = true
                    return@withLock
                }
                pushService.registerWith(
                    matrixClient = matrixClient,
                    pushProvider = selfRegistration.first,
                    distributor = selfRegistration.second,
                ).fold(
                    onSuccess = {
                        saveFailed = false
                        showServerDialog = false
                        refreshDistributor++
                    },
                    onFailure = {
                        Timber.w(it, "Failed to re-register built-in push after server change")
                        selfPushStore.setCustomServer(previousServer)
                        selfPushStore.setReadToken(previousToken)
                        serverRefresh++
                        saveFailed = true
                    },
                )
            }
        }

        fun CoroutineScope.checkServerHealth(server: String, readToken: String?) = launch {
            healthCheckServer = server
            healthCheckToken = readToken?.trim()?.takeUnless { it.isEmpty() }
            healthCheck = AsyncData.Loading()
            val result = runCatchingExceptions {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder().url("$server/v1/health").apply {
                        // Token-protected servers answer 401 without it, which is not "unreachable".
                        val token = readToken?.trim()?.takeUnless { it.isEmpty() }
                        if (token != null) header("Authorization", "Bearer $token")
                    }.build()
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Health check failed with HTTP ${response.code}")
                    }
                }
            }
            // Drop the result if a newer check superseded this one or the dialog was reopened.
            if (healthCheckServer == server && healthCheckToken == readToken?.trim()?.takeUnless { it.isEmpty() }) {
                healthCheck = result.fold(
                    onSuccess = { AsyncData.Success(Unit) },
                    onFailure = { AsyncData.Failure(it) },
                )
            }
        }

        fun handleEvent(event: SelfPushSettingsEvent) {
            when (event) {
                SelfPushSettingsEvent.RefreshStatus -> {
                    batteryOptimizationIgnored = batteryOptimization.isIgnoringBatteryOptimizations()
                    statusRefresh++
                }
                SelfPushSettingsEvent.UseBuiltInPush -> localCoroutineScope.launch {
                    val selfRegistration = selfDistributor() ?: return@launch
                    pushService.registerWith(
                        matrixClient = matrixClient,
                        pushProvider = selfRegistration.first,
                        distributor = selfRegistration.second,
                    ).onSuccess { refreshDistributor++ }
                }
                SelfPushSettingsEvent.ShowServerDialog -> {
                    healthCheck = AsyncData.Uninitialized
                    healthCheckServer = null
                    healthCheckToken = null
                    saveFailed = false
                    showServerDialog = true
                }
                SelfPushSettingsEvent.DismissServerDialog -> showServerDialog = false
                is SelfPushSettingsEvent.SaveServer -> {
                    val url = event.url.trim()
                    if (isValidSelfPushServerUrl(url)) {
                        localCoroutineScope.applyServer(url, event.readToken?.trim()?.takeUnless { it.isEmpty() })
                    } else {
                        Timber.w("Rejected invalid built-in push server URL")
                        saveFailed = true
                    }
                }
                SelfPushSettingsEvent.ResetServer -> {
                    localCoroutineScope.applyServer(null, null)
                }
                is SelfPushSettingsEvent.CheckServerHealth -> {
                    localCoroutineScope.checkServerHealth(event.url.trim().trimEnd('/'), event.readToken)
                }
                SelfPushSettingsEvent.ShowProtocolDialog -> showProtocolDialog = true
                SelfPushSettingsEvent.DismissProtocolDialog -> showProtocolDialog = false
                is SelfPushSettingsEvent.SetProtocol -> {
                    selfPushStore.setProtocol(event.protocol)
                    protocolRefresh++
                    showProtocolDialog = false
                    selfPushStreamRefresher.refresh()
                }
                SelfPushSettingsEvent.RequestDisableBatteryOptimization -> {
                    batteryOptimization.requestDisablingBatteryOptimization()
                }
            }
        }

        return SelfPushSettingsState(
            isActive = isActive,
            server = key(serverRefresh, statusRefresh) {
                val customServer = selfPushStore.getCustomServer()
                SelfPushSettingsState.ServerState(
                    customServer = customServer,
                    effectiveServer = customServer ?: DEFAULT_SELF_PUSH_SERVER,
                    showDialog = showServerDialog,
                    healthCheck = healthCheck,
                    healthCheckServer = healthCheckServer,
                    healthCheckToken = healthCheckToken,
                    saveFailed = saveFailed,
                    readToken = selfPushStore.getReadToken(),
                )
            },
            protocol = key(protocolRefresh) {
                SelfPushSettingsState.ProtocolState(
                    protocol = selfPushStore.getProtocol(),
                    showDialog = showProtocolDialog,
                )
            },
            listenerStatus = key(statusRefresh, liveStreamStatuses, isActive) {
                computeListenerStatus()
            },
            batteryOptimizationIgnored = batteryOptimizationIgnored,
            eventSink = ::handleEvent,
        )
    }
}
