/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.advanced

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.zacsweers.metro.Inject
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import io.element.android.libraries.preferences.api.store.UrlPreviewValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Inject
class AdvancedSettingsPresenter(
    private val appPreferencesStore: AppPreferencesStore,
    @SessionCoroutineScope
    private val sessionCoroutineScope: CoroutineScope,
) : Presenter<AdvancedSettingsState> {
    @Composable
    override fun present(): AdvancedSettingsState {
        val hideRedactedEvents by remember {
            appPreferencesStore.getHideRedactedEventsFlow()
        }.collectAsState(initial = false)

        val mediaAutoDownloadOnWifiOnly by remember {
            appPreferencesStore.getMediaAutoDownloadOnWifiOnlyFlow()
        }.collectAsState(initial = false)

        val urlPreviewValue by remember {
            appPreferencesStore.getUrlPreviewValueFlow()
        }.collectAsState(initial = UrlPreviewValue.DEFAULT)

        fun handleEvent(event: AdvancedSettingsEvent) {
            when (event) {
                is AdvancedSettingsEvent.SetUrlPreviewValue -> sessionCoroutineScope.launch {
                    appPreferencesStore.setUrlPreviewValue(event.value)
                }
                is AdvancedSettingsEvent.SetHideRedactedEvents -> sessionCoroutineScope.launch {
                    appPreferencesStore.setHideRedactedEvents(event.value)
                }
                is AdvancedSettingsEvent.SetMediaAutoDownloadOnWifiOnly -> sessionCoroutineScope.launch {
                    appPreferencesStore.setMediaAutoDownloadOnWifiOnly(event.value)
                }
            }
        }

        return AdvancedSettingsState(
            urlPreviewValue = urlPreviewValue,
            hideRedactedEvents = hideRedactedEvents,
            mediaAutoDownloadOnWifiOnly = mediaAutoDownloadOnWifiOnly,
            eventSink = ::handleEvent,
        )
    }
}
