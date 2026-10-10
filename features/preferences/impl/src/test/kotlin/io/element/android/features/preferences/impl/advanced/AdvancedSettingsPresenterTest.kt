/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.advanced

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import io.element.android.libraries.preferences.api.store.UrlPreviewValue
import io.element.android.libraries.preferences.test.InMemoryAppPreferencesStore
import io.element.android.tests.testutils.WarmUpRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class AdvancedSettingsPresenterTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `present - initial state`() = runTest {
        val presenter = createAdvancedSettingsPresenter()
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            with(awaitItem()) {
                assertThat(urlPreviewValue).isEqualTo(UrlPreviewValue.UnencryptedOnly)
                assertThat(hideRedactedEvents).isFalse()
                assertThat(mediaAutoDownloadOnWifiOnly).isFalse()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - setting url preview value updates app preferences`() = runTest {
        val appPreferencesStore = InMemoryAppPreferencesStore()
        val presenter = createAdvancedSettingsPresenter(appPreferencesStore = appPreferencesStore)
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            with(awaitItem()) {
                assertThat(urlPreviewValue).isEqualTo(UrlPreviewValue.UnencryptedOnly)
                eventSink(AdvancedSettingsEvent.SetUrlPreviewValue(UrlPreviewValue.Off))
            }
            with(awaitItem()) {
                assertThat(urlPreviewValue).isEqualTo(UrlPreviewValue.Off)
                eventSink(AdvancedSettingsEvent.SetUrlPreviewValue(UrlPreviewValue.On))
            }
            with(awaitItem()) {
                assertThat(urlPreviewValue).isEqualTo(UrlPreviewValue.On)
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - setting hide redacted events updates app preferences`() = runTest {
        val appPreferencesStore = InMemoryAppPreferencesStore()
        val presenter = createAdvancedSettingsPresenter(appPreferencesStore = appPreferencesStore)
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            with(awaitItem()) {
                assertThat(hideRedactedEvents).isFalse()
                eventSink(AdvancedSettingsEvent.SetHideRedactedEvents(true))
            }
            with(awaitItem()) {
                assertThat(hideRedactedEvents).isTrue()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - setting wifi only media auto download updates app preferences`() = runTest {
        val appPreferencesStore = InMemoryAppPreferencesStore()
        val presenter = createAdvancedSettingsPresenter(appPreferencesStore = appPreferencesStore)
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            with(awaitItem()) {
                assertThat(mediaAutoDownloadOnWifiOnly).isFalse()
                eventSink(AdvancedSettingsEvent.SetMediaAutoDownloadOnWifiOnly(true))
            }
            with(awaitItem()) {
                assertThat(mediaAutoDownloadOnWifiOnly).isTrue()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun CoroutineScope.createAdvancedSettingsPresenter(
        appPreferencesStore: AppPreferencesStore = InMemoryAppPreferencesStore(),
    ) = AdvancedSettingsPresenter(
        appPreferencesStore = appPreferencesStore,
        sessionCoroutineScope = this,
    )
}
