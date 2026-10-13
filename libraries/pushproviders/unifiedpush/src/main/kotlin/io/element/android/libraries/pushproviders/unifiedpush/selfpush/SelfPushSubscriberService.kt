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
import java.util.concurrent.atomic.AtomicBoolean

private val loggerTag = LoggerTag("SelfPushSubscriberService", LoggerTag.PushLoggerTag)

private const val NOTIFICATION_ID = 1007
private const val CHANNEL_ID = "self_push_listener_channel"
private const val OUTAGE_NOTIFICATION_ID = 1008
private const val ALERT_CHANNEL_ID = "self_push_listener_alert_channel"
private const val WATCHDOG_REQUEST_CODE = 42
private const val OUTAGE_REQUEST_CODE = 43

/**
 * Keeps the built-in push listener alive: one streaming connection per notification server,
 * covering every registered session's topic.
 *
 * Modeled on the ntfy subscriber service: foreground, sticky, restarted on boot and on
 * network change, with a watchdog alarm reviving it in case the system froze the process
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
    @Inject lateinit var streamStates: SelfPushStreamStates

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private data class ActiveStream(
        val job: Job,
        val protocol: SelfPushProtocol,
        val readToken: String?,
        val topics: Set<String>,
        /** Flipped by the client's onOpen: the handshake completed and frames are flowing. */
        val opened: AtomicBoolean = AtomicBoolean(false),
    )

    /** One streaming job per notification server in use. */
    private val streamJobs = mutableMapOf<String, ActiveStream>()

    private val recentMessageIds = RecentMessageIds()

    /** When each server's stream first broke, the base for the outage alert; cleared on reconnect. */
    private val outageStartTimes = mutableMapOf<String, Long>()

    /** Servers the user was already alerted about during the current outage. */
    private val outageAlertedServers = mutableSetOf<String>()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // A network switch can leave a socket dead without closing it; restart the streams.
            restartStreams(force = true)
        }

        override fun onLost(network: Network) {
            // No default route left: park the streams instead of spinning reconnects into
            // the void; onAvailable brings them back.
            val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return
            if (connectivityManager.activeNetwork == null) {
                Timber.tag(loggerTag.value).d("No network left, parking the streams")
                parkStreams()
            }
        }
    }

    @Synchronized
    private fun parkStreams() {
        streamJobs.forEach { (server, stream) ->
            stream.job.cancel()
            selfPushStore.setStreamOpen(server, false)
            streamStates.set(server, SelfPushStreamStatus.NO_NETWORK)
        }
        streamJobs.clear()
        updateForegroundNotification()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        restartStreams(force = false)
        scheduleWatchdog()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Xiaomi-class OEMs kill the process on a recents swipe; ntfy's answer is the same:
        // a near-term alarm brings the listener back before the system finishes the teardown.
        if (selfPushStore.getAllRegistrations().isNotEmpty()) {
            scheduleWatchdog(QUICK_RESTART_DELAY_MS)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        streamJobs.keys.forEach { streamStates.remove(it) }
        getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback)
        // START_STICKY covers most kills, but not all OEMs honor it; re-arm the watchdog one
        // last time if there is still something to listen to. A modest delay, not the quick
        // one: a destroy caused by a foreground-start failure must not retry in a tight loop.
        if (selfPushStore.getAllRegistrations().isNotEmpty()) {
            scheduleWatchdog(DESTROY_RESTART_DELAY_MS)
        } else {
            cancelWatchdog()
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * Brings the running streams in line with the store, per [planStreams]: opens streams for
     * servers that gained a registration, drops the ones that lost theirs, reopens the ones
     * whose protocol, token or topics changed under them, and stops the service entirely when
     * nothing is left to listen to. [force] reopens everything, for when the network under
     * the sockets may have changed.
     */
    @Synchronized
    private fun restartStreams(force: Boolean) {
        val registrations = selfPushStore.getAllRegistrations()
        val registrationsByServer = registrations.entries.groupBy({ it.value.server }) { it.value.topic }
        if (registrationsByServer.isEmpty()) {
            Timber.tag(loggerTag.value).d("No registrations left, stopping")
            stopSelf()
            return
        }

        val protocol = selfPushStore.getProtocol()
        val configuredServer = (selfPushStore.getCustomServer() ?: DEFAULT_SELF_PUSH_SERVER).trimEnd('/')
        val storedToken = selfPushStore.getReadToken()
        // The token belongs to the configured server only; in a mixed state (a failed
        // migration leaving registrations on two servers) it must not leak to the other one.
        val plan = planStreams(
            registrationsByServer = registrationsByServer.mapValues { it.value.toSet() },
            active = streamJobs.mapValues { (_, stream) ->
                ActiveStreamInfo(stream.protocol, stream.readToken, stream.topics, stream.job.isActive)
            },
            protocol = protocol,
            readTokenFor = { server -> storedToken.takeIf { server == configuredServer } },
            force = force,
        )

        plan.toCancel.forEach { server ->
            Timber.tag(loggerTag.value).d("Dropping the stream to $server")
            streamJobs.remove(server)?.job?.cancel()
            selfPushStore.setStreamOpen(server, false)
            streamStates.remove(server)
            clearOutage(server)
        }

        for ((server, topicNames) in plan.toOpen) {
            val readToken = storedToken.takeIf { server == configuredServer }
            Timber.tag(loggerTag.value).d("Opening a $protocol stream to $server for ${topicNames.size} topic(s)")
            selfPushStore.setStreamOpen(server, false)
            streamStates.set(server, SelfPushStreamStatus.CONNECTING)
            val opened = AtomicBoolean(false)
            streamJobs[server] = ActiveStream(
                job = serviceScope.launch {
                    streamClient.stream(
                        server = server,
                        topics = topicNames,
                        readToken = readToken,
                        protocol = protocol,
                        onOpen = {
                            opened.set(true)
                            selfPushStore.setStreamOpen(server, true)
                            streamStates.set(server, SelfPushStreamStatus.CONNECTED)
                            clearOutage(server)
                            updateForegroundNotification()
                        },
                        onReconnecting = {
                            selfPushStore.setStreamOpen(server, false)
                            streamStates.set(server, SelfPushStreamStatus.RECONNECTING)
                            markOutage(server)
                            updateForegroundNotification()
                        },
                        since = { catchUpSince(topicNames) },
                    )
                        .catch { cause ->
                            if (cause is FatalStreamException) {
                                Timber.tag(loggerTag.value).e(cause, "Stream to $server refused, giving up until the next refresh")
                                streamStates.set(server, SelfPushStreamStatus.REFUSED)
                                markOutage(server)
                                updateForegroundNotification()
                            } else {
                                throw cause
                            }
                        }
                        .collect { message -> onPushMessage(server, message) }
                },
                protocol = protocol,
                readToken = readToken,
                topics = topicNames,
                opened = opened,
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
        // Persisted guard first (survives process death): strictly older than what we stored
        // means replayed. Same-second pushes pass through to the in-memory dedupe below.
        val storedTime = selfPushStore.getLastMessageTime(message.topic)
        if (storedTime != null && message.time < storedTime) {
            Timber.tag(loggerTag.value).d("Replayed push older than the stored watermark, dropping")
            return
        }
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

    private fun scheduleWatchdog(delayMs: Long? = null) {
        maybePostOutageAlert()
        val alarmManager = getSystemService(AlarmManager::class.java) ?: return
        runCatchingExceptions {
            // A healthy listener needs no tight cadence: the stream heals itself and the
            // watchdog only revives a frozen process, so back off to hourly while all is well.
            val healthy = streamJobs.isNotEmpty() && streamJobs.values.all { it.job.isActive && it.opened.get() }
            val interval = delayMs ?: if (healthy) HEALTHY_WATCHDOG_INTERVAL_MS else WATCHDOG_INTERVAL_MS
            val triggerAt = System.currentTimeMillis() + interval
            // SCHEDULE_EXACT_ALARM can be revoked on Android 12+; fall back to an inexact
            // alarm instead of losing the watchdog to a SecurityException. The fallback only
            // revives a live process: starting a foreground service from background is an
            // exact-alarm exemption, so with the permission revoked a dead process relies on
            // the sticky restart, the boot receiver and the app-startup hook.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, watchdogIntent())
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, watchdogIntent())
            }
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
                    .build(),
                NotificationChannelCompat.Builder(ALERT_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(getString(CommonStrings.common_push_listener_alert_channel_name_android))
                    .build()
            )
        )
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(CommonStrings.common_push_listener_notification_android))
        .apply {
            val statusText = listenerStatusText()
            if (statusText != null) setContentText(statusText)
        }
        .setOngoing(true)
        .build()

    /** Mirrors the ntfy app: the ongoing notification tells what the listener is doing right now. */
    private fun listenerStatusText(): String? {
        return when (streamStates.states.value.values.worstSelfPushStatus()) {
            SelfPushStreamStatus.REFUSED -> getString(CommonStrings.common_push_listener_notification_refused_android)
            SelfPushStreamStatus.RECONNECTING,
            SelfPushStreamStatus.CONNECTING -> getString(CommonStrings.common_push_listener_notification_reconnecting_android)
            SelfPushStreamStatus.NO_NETWORK -> getString(CommonStrings.common_push_listener_notification_no_network_android)
            SelfPushStreamStatus.CONNECTED -> getString(CommonStrings.common_push_listener_notification_connected_android)
            null -> null
        }
    }

    private fun updateForegroundNotification() {
        runCatchingExceptions {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        }.onFailure { Timber.tag(loggerTag.value).w(it, "Could not update the listener notification") }
    }

    private fun markOutage(server: String) {
        outageStartTimes.putIfAbsent(server, System.currentTimeMillis())
    }

    private fun clearOutage(server: String) {
        outageStartTimes.remove(server)
        outageAlertedServers.remove(server)
        if (outageAlertedServers.isEmpty()) {
            runCatchingExceptions {
                NotificationManagerCompat.from(this).cancel(OUTAGE_NOTIFICATION_ID)
            }
        }
    }

    /**
     * Surfaces a long outage as a dismissible alert, like the ntfy app's connection alert:
     * a broken stream heals quietly, but one down past [OUTAGE_ALERT_THRESHOLD_MS] is likely a
     * dead server or a revoked token, which no reconnect will fix on its own. Evaluated on the
     * watchdog ticks, so the alert lands within one tick past the threshold.
     */
    private fun maybePostOutageAlert() {
        val now = System.currentTimeMillis()
        val due = outageStartTimes.keys.filter { server ->
            server !in outageAlertedServers && now - (outageStartTimes[server] ?: now) >= OUTAGE_ALERT_THRESHOLD_MS
        }
        if (due.isEmpty()) return
        outageAlertedServers.addAll(due)
        val hosts = due.joinToString { server ->
            server.substringAfter("://").substringBefore('/')
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(CommonStrings.common_push_listener_alert_title_android))
            .setContentText(getString(CommonStrings.common_push_listener_alert_text_android, hosts))
            .setAutoCancel(true)
            .apply {
                if (launchIntent != null) {
                    setContentIntent(
                        PendingIntent.getActivity(
                            this@SelfPushSubscriberService,
                            OUTAGE_REQUEST_CODE,
                            launchIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                    )
                }
            }
            .build()
        runCatchingExceptions {
            NotificationManagerCompat.from(this).notify(OUTAGE_NOTIFICATION_ID, notification)
        }.onFailure { Timber.tag(loggerTag.value).w(it, "Could not post the outage alert") }
    }

    companion object {
        private const val ACTION_REFRESH = "io.element.android.libraries.pushproviders.unifiedpush.selfpush.action.REFRESH"
        private const val ACTION_WATCHDOG = "io.element.android.libraries.pushproviders.unifiedpush.selfpush.action.WATCHDOG"

        /** The stream heals itself between watchdogs; this only revives a frozen process. */
        private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L

        /** Watchdog cadence while every stream is up and flowing. */
        private const val HEALTHY_WATCHDOG_INTERVAL_MS = 60 * 60 * 1000L

        /** How far back the catch-up replay reaches past the last seen push. */
        private const val SINCE_OVERLAP_S = 2L

        /** Swipe-kill re-arm delay. */
        private const val QUICK_RESTART_DELAY_MS = 1000L

        /** Post-destroy re-arm delay. */
        private const val DESTROY_RESTART_DELAY_MS = 30_000L

        /** How long a stream stays broken before the user gets an outage alert. */
        private const val OUTAGE_ALERT_THRESHOLD_MS = 30 * 60 * 1000L

        /** Starts (or refreshes) the listener. Safe to call when it is already running. */
        fun start(context: Context) {
            val intent = Intent(context, SelfPushSubscriberService::class.java).setAction(ACTION_REFRESH)
            runCatchingExceptions {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { Timber.tag(loggerTag.value).w(it, "Could not start the listener now") }
        }
    }
}
