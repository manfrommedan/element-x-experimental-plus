/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.postDelayed
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumble.appyx.core.integrationpoint.NodeActivity
import com.bumble.appyx.core.plugin.NodeReadyObserver
import io.element.android.compound.colors.SemanticColorsLightDark
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.lockscreen.api.LockScreenEntryPoint
import io.element.android.features.lockscreen.api.LockScreenLockState
import io.element.android.features.lockscreen.api.LockScreenService
import io.element.android.features.lockscreen.api.handleSecureFlag
import io.element.android.libraries.architecture.appyx.DebugNavStateNodeHost
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.core.log.logger.LoggerTag
import io.element.android.libraries.designsystem.theme.ElementThemeApp
import io.element.android.libraries.designsystem.utils.snackbar.LocalSnackbarDispatcher
import io.element.android.services.analytics.compose.LocalAnalyticsService
import io.element.android.x.di.AppBindings
import io.element.android.x.intent.SafeUriHandler
import kotlinx.coroutines.launch
import timber.log.Timber

private val loggerTag = LoggerTag("MainActivity")

/**
 * Safety net: never keep the system splash screen longer than this, whatever the state of the app.
 */
private const val MAX_SPLASH_SCREEN_DURATION_MS = 5_000L

/** Grace period after the deadline before a frame is forced to re-evaluate the keep condition. */
private const val SPLASH_RELEASE_GRACE_MS = 50L

open class MainActivity : NodeActivity() {
    private lateinit var mainNode: MainNode
    private lateinit var appBindings: AppBindings
    private var isMigrationScreenDisplayed = false
    private var splashScreenDeadline = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        Timber.tag(loggerTag.value).d("onCreate, with savedInstanceState: ${savedInstanceState != null}")
        splashScreenDeadline = SystemClock.elapsedRealtime() + MAX_SPLASH_SCREEN_DURATION_MS
        installSplashScreen().setKeepOnScreenCondition(::shouldKeepSplashScreen)
        super.onCreate(savedInstanceState)
        // The condition is only evaluated when a frame is about to be drawn, so make sure one is requested once the deadline is over.
        window.decorView.postDelayed(MAX_SPLASH_SCREEN_DURATION_MS + SPLASH_RELEASE_GRACE_MS) { window.decorView.invalidate() }
        appBindings = bindings()
        setupLockManagement(appBindings.lockScreenService(), appBindings.lockScreenEntryPoint())
        enableEdgeToEdge()
        setContent {
            MainContent(appBindings)
        }

        val activity = this
        appBindings.appStartupHooks().forEach {
            lifecycleScope.launch {
                it.onAppStartup(activity)
            }
        }
    }

    /**
     * Keep the system splash screen until the session is restored, instead of drawing an empty screen
     * between the first frame and the first real screen.
     */
    private fun shouldKeepSplashScreen(): Boolean {
        return when {
            SystemClock.elapsedRealtime() > splashScreenDeadline -> false
            isMigrationScreenDisplayed -> false
            ::mainNode.isInitialized -> mainNode.isShowingSplashScreen()
            else -> true
        }
    }

    @Composable
    private fun MainContent(appBindings: AppBindings) {
        val migrationState = appBindings.migrationEntryPoint().present()
        SideEffect {
            isMigrationScreenDisplayed = migrationState.migrationAction.isLoading() || migrationState.migrationAction.isFailure()
        }
        Timber.tag(loggerTag.value).d(
            "migrationAction=${migrationState.migrationAction::class.simpleName} " +
                "isSuccess=${migrationState.migrationAction.isSuccess()}"
        )
        val colors by remember {
            appBindings.enterpriseService().semanticColorsFlow(sessionId = null)
        }.collectAsState(SemanticColorsLightDark.default)
        ElementThemeApp(
            appPreferencesStore = appBindings.preferencesStore(),
            featureFlagService = appBindings.featureFlagService(),
            compoundLight = colors.light,
            compoundDark = colors.dark,
            buildMeta = appBindings.buildMeta()
        ) {
            // Wifi-only auto-download is force-disabled while the matrix media
            // cache survival across app restarts is being sorted out. The
            // toggle UI is also hidden in AdvancedSettingsView. Any preference
            // value previously toggled on in earlier builds is ignored here -
            // existing users won't see their old setting still gate downloads.
            val autoLoadMedia = true
            CompositionLocalProvider(
                LocalSnackbarDispatcher provides appBindings.snackbarDispatcher(),
                LocalUriHandler provides SafeUriHandler(this),
                LocalAnalyticsService provides appBindings.analyticsService(),
                io.element.android.libraries.designsystem.components.media.LocalAutoLoadMedia provides autoLoadMedia,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ElementTheme.colors.bgCanvasDefault),
                ) {
                    if (migrationState.migrationAction.isSuccess()) {
                        Timber.tag(loggerTag.value).d("rendering MainNodeHost (migration success)")
                        MainNodeHost()
                    } else {
                        Timber.tag(loggerTag.value).d("rendering Migration screen (action=${migrationState.migrationAction::class.simpleName})")
                        appBindings.migrationEntryPoint().Render(
                            state = migrationState,
                            modifier = Modifier,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun MainNodeHost() {
        // TODO this is a temporary helper to capture the nav state in a more readable format for crash reports
        // Revert to `NodeHost` once this is fixed
        DebugNavStateNodeHost(integrationPoint = appyxV1IntegrationPoint) {
            MainNode(
                it,
                plugins = listOf(
                    object : NodeReadyObserver<MainNode> {
                        override fun init(node: MainNode) {
                            Timber.tag(loggerTag.value).d("onMainNodeInit")
                            mainNode = node
                            mainNode.handleIntent(intent)
                        }
                    },
                ),
                context = applicationContext
            )
        }
    }

    private fun setupLockManagement(
        lockScreenService: LockScreenService,
        lockScreenEntryPoint: LockScreenEntryPoint
    ) {
        lockScreenService.handleSecureFlag(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                lockScreenService.lockState.collect { state ->
                    if (state == LockScreenLockState.Locked) {
                        startActivity(lockScreenEntryPoint.pinUnlockIntent(this@MainActivity))
                    }
                }
            }
        }
    }

    /**
     * Called when:
     * - the launcher icon is clicked (if the app is already running);
     * - a notification is clicked.
     * - a deep link have been clicked
     * - the app is going to background (<- this is strange)
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Timber.tag(loggerTag.value).d("onNewIntent")
        // If the mainNode is not init yet, keep the intent for later.
        // It can happen when the activity is killed by the system. The methods are called in this order :
        // onCreate(savedInstanceState=true) -> onNewIntent -> onResume -> onMainNodeInit
        if (::mainNode.isInitialized) {
            mainNode.handleIntent(intent)
        } else {
            setIntent(intent)
        }
    }

    override fun onPause() {
        super.onPause()
        Timber.tag(loggerTag.value).d("onPause")
    }

    override fun onResume() {
        super.onResume()
        Timber.tag(loggerTag.value).d("onResume")
    }

    override fun onStop() {
        super.onStop()
        Timber.tag(loggerTag.value).d("onStop (isFinishing=$isFinishing isChangingConfigurations=$isChangingConfigurations)")
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.tag(loggerTag.value).d("onDestroy (isFinishing=$isFinishing isChangingConfigurations=$isChangingConfigurations)")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Timber.tag(loggerTag.value).d("onTrimMemory level=$level")
    }
}
