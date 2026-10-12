/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.selfpush

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.zacsweers.metro.Inject
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.log.logger.LoggerTag
import io.element.android.libraries.push.api.push.FetchPushForegroundServiceManager
import io.element.android.libraries.pushproviders.api.PushHandler
import io.element.android.libraries.pushproviders.unifiedpush.R
import io.element.android.libraries.pushproviders.unifiedpush.UnifiedPushConfig
import io.element.android.libraries.pushproviders.unifiedpush.UnifiedPushParser
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import timber.log.Timber

private val loggerTag = LoggerTag("SelfPushSubscriberService", LoggerTag.PushLoggerTag)

private const val NOTIFICATION_ID = 1007
private const val CHANNEL_ID = "self_push_listener_channel"
private const val WATCHDOG_REQUEST_CODE = 42

/**
 * Keeps the built-in push listener alive: one streaming connection per notification server,
 * covering every registered session's topic.
 *
 * Modeled on the ntfy subscriber service: foreground, sticky, restarted on boot and on
 * network change, with a watchdog alarm poking it in case the system froze the process
 * between those events. Reconnects replay what the server cached while the stream was down
 * (the `since` parameter), so pushes landing in a reconnect window are not lost. The ongoing
 * notification sits on a minimum-importance channel the user can hide from the system
 * settings, the same as the ntfy app's own.
 */
class SelfPushSubscriberService : Service() {
    @Inject lateinit var selfPushStore: SelfPushStore
    @Inject lateinit var streamClient: NtfyStreamClient
    @Inject lateinit var pushParser: UnifiedPushParser
    @Inject lateinit var pushHandler: PushHandler
    @Inject lateinit var fetchPushForegroundServiceManager: FetchPushForegroundServiceManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private data class ActiveStream(val job: Job, val protocol: SelfPushProtocol)

    /** One streaming job per notification server in use. */
    private val streamJobs = mutableMapOf<String, ActiveStream>()

    private val recentMessageIds = RecentMessageIds()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // A network switch can leave a socket dead without closing it; restart the streams.
            restartStreams(force = true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        if (!this::selfPushStore.isInitialized) {
            bindings<SelfPushSubscriberServiceBindings>().inject(this)
        }
        ensureNotificationChannelExists()
        runCatchingExceptions {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), serviceType)
        }.onFailure {
            Timber.tag(loggerTag.value).e(it, "Could not go foreground, stopping")
            stopSelf()
            return
        }
        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(networkCallback)
        scheduleWatchdog()
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Timber.tag(loggerTag.value).d("Stopped on request")
                stopSelf()
            }
            else -> {
                restartStreams(force = false)
                scheduleWatchdog()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback)
        cancelWatchdog()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * Brings the running streams in line with the store: opens streams for servers that gained
     * a registration, drops the ones that lost theirs, reopens the ones whose protocol changed
     * under them, and stops the service entirely when nothing is left to listen to. [force]
     * reopens everything, for when the network under the sockets may have changed.
     */
    @Synchronized
    private fun restartStreams(force: Boolean) {
        val registrations = selfPushStore.getAllRegistrations()
        val registrationsByServer = registrations.entries.groupBy({ it.value.server }) { it.key to it.value.topic }
        if (registrationsByServer.isEmpty()) {
            Timber.tag(loggerTag.value).d("No registrations left, stopping")
            stopSelf()
            return
        }

        val staleServers = streamJobs.keys - registrationsByServer.keys
        staleServers.forEach { server ->
            Timber.tag(loggerTag.value).d("Dropping the stream to $server")
            streamJobs.remove(server)?.job?.cancel()
        }

        val protocol = selfPushStore.getProtocol()
        val readToken = selfPushStore.getReadToken()
        for ((server, topics) in registrationsByServer) {
            // A job that died on a fatal refusal counts as absent, so the watchdog can revive it.
            val active = streamJobs[server]?.takeIf { it.job.isActive }
            if (active != null && !force && active.protocol == protocol) continue
            if (active != null) {
                Timber.tag(loggerTag.value).d("Reopening the stream to $server")
                active.job.cancel()
            }
            val topicNames = topics.map { it.second }.toSet()
            Timber.tag(loggerTag.value).d("Opening a $protocol stream to $server for ${topicNames.size} topic(s)")
            streamJobs[server] = ActiveStream(
                job = serviceScope.launch {
                    streamClient.stream(server, topicNames, readToken, protocol, since = { catchUpSince(topicNames) })
                        .catch { cause ->
                            if (cause is FatalStreamException) {
                                Timber.tag(loggerTag.value).e(cause, "Stream to $server refused, giving up until the next refresh")
                            } else {
                                throw cause
                            }
                        }
                        .collect { message -> onPushMessage(server, message) }
                },
                protocol = protocol,
            )
        }
    }

    /**
     * The oldest push timestamp seen across the server's topics, minus a small overlap:
     * replays everything the server cached since then, and [recentMessageIds] filters the
     * overlap back out.
     */
    private fun catchUpSince(topics: Set<String>): Long? {
        return topics.mapNotNull { selfPushStore.getLastMessageTime(it) }
            .minOrNull()
            ?.let { it - SINCE_OVERLAP_S }
    }

    private suspend fun onPushMessage(server: String, message: NtfyMessage) {
        if (!recentMessageIds.addIfNew("$server:${message.id}")) {
            Timber.tag(loggerTag.value).d("Replayed push, dropping")
            return
        }
        selfPushStore.storeLastMessageTime(message.topic, message.time)

        val clientSecret = selfPushStore.getAllRegistrations().entries
            .firstOrNull { it.value.server == server && it.value.topic == message.topic }
            ?.key
        if (clientSecret == null) {
            Timber.tag(loggerTag.value).w("Push on an unknown topic, dropping")
            return
        }

        fetchPushForegroundServiceManager.start()
        val providerInfo = "${UnifiedPushConfig.NAME} built-in - $clientSecret"
        val pushData = pushParser.parse(message.content.toByteArray(Charsets.UTF_8), clientSecret)
        if (pushData == null) {
            Timber.tag(loggerTag.value).w("Unreadable push content")
            pushHandler.handleInvalid(providerInfo = providerInfo, data = message.content)
            fetchPushForegroundServiceManager.stop()
        } else {
            val handled = pushHandler.handle(pushData = pushData, providerInfo = providerInfo)
            if (!handled) fetchPushForegroundServiceManager.stop()
        }
    }

    private fun scheduleWatchdog() {
        val alarmManager = getSystemService(AlarmManager::class.java) ?: return
        runCatchingExceptions {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + WATCHDOG_INTERVAL_MS,
                watchdogIntent(),
            )
        }.onFailure { Timber.tag(loggerTag.value).w(it, "Could not schedule the watchdog") }
    }

    private fun cancelWatchdog() {
        getSystemService(AlarmManager::class.java)?.cancel(watchdogIntent())
    }

    private fun watchdogIntent(): PendingIntent {
        val intent = Intent(this, SelfPushSubscriberService::class.java).setAction(ACTION_WATCHDOG)
        return PendingIntent.getForegroundService(this, WATCHDOG_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun ensureNotificationChannelExists() {
        NotificationManagerCompat.from(this).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_MIN)
                    .setName(getString(CommonStrings.common_push_listener_channel_name_android))
                    .setVibrationEnabled(false)
                    .setSound(null, null)
                    .build()
            )
        )
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(CommonStrings.common_push_listener_notification_android))
        .setOngoing(true)
        .build()

    companion object {
        private const val ACTION_REFRESH = "io.element.android.libraries.pushproviders.unifiedpush.selfpush.action.REFRESH"
        private const val ACTION_WATCHDOG = "io.element.android.libraries.pushproviders.unifiedpush.selfpush.action.WATCHDOG"
        private const val ACTION_STOP = "io.element.android.libraries.pushproviders.unifiedpush.selfpush.action.STOP"

        /** The stream heals itself between watchdogs; this only revives a frozen process. */
        private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L

        /** How far back the catch-up replay reaches past the last seen push. */
        private const val SINCE_OVERLAP_S = 2L

        /** Starts (or refreshes) the listener. Safe to call when it is already running. */
        fun start(context: Context) {
            val intent = Intent(context, SelfPushSubscriberService::class.java).setAction(ACTION_REFRESH)
            runCatchingExceptions {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { Timber.tag(loggerTag.value).w(it, "Could not start the listener now") }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SelfPushSubscriberService::class.java).setAction(ACTION_STOP))
        }
    }
}
