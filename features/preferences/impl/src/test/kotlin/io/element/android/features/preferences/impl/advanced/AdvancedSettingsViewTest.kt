/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.preferences.impl.advanced

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import io.element.android.features.preferences.impl.R
import io.element.android.libraries.preferences.api.store.UrlPreviewValue
import io.element.android.tests.testutils.EnsureNeverCalled
import io.element.android.tests.testutils.EventsRecorder
import io.element.android.tests.testutils.clickOn
import io.element.android.tests.testutils.ensureCalledOnce
import io.element.android.tests.testutils.pressBack
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test
import org.robolectric.annotation.Config

class AdvancedSettingsViewTest : RobolectricTest() {
    @Test
    fun `clicking on back invokes the expected callback`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<AdvancedSettingsEvent>(expectEvents = false)
        ensureCalledOnce {
            setAdvancedSettingsView(
                state = aAdvancedSettingsState(
                    eventSink = eventsRecorder
                ),
                onBackClick = it
            )
            pressBack()
        }
    }

    @Test
    fun `clicking on hide redacted events emits the expected event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<AdvancedSettingsEvent>()
        setAdvancedSettingsView(
            state = aAdvancedSettingsState(
                eventSink = eventsRecorder,
                hideRedactedEvents = false,
            ),
        )
        clickOn(R.string.screen_advanced_settings_hide_redacted_events_toggle_title)
        eventsRecorder.assertSingle(AdvancedSettingsEvent.SetHideRedactedEvents(true))
    }

    @Test
    @Config(qualifiers = "h1080dp")
    fun `clicking on a link preview option emits the expected event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<AdvancedSettingsEvent>()
        setAdvancedSettingsView(
            state = aAdvancedSettingsState(
                eventSink = eventsRecorder,
                urlPreviewValue = UrlPreviewValue.UnencryptedOnly,
            ),
        )
        clickOn(R.string.screen_advanced_settings_show_link_previews_all)
        eventsRecorder.assertSingle(AdvancedSettingsEvent.SetUrlPreviewValue(UrlPreviewValue.On))
    }

    @Test
    fun `clicking on mxtr settings invokes the expected callback`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<AdvancedSettingsEvent>(expectEvents = false)
        ensureCalledOnce {
            setAdvancedSettingsView(
                state = aAdvancedSettingsState(
                    eventSink = eventsRecorder
                ),
                onOpenMxtrSettings = it
            )
            clickOn(R.string.screen_advanced_settings_mxtr_title)
        }
    }
}

private fun AndroidComposeUiTest<ComponentActivity>.setAdvancedSettingsView(
    state: AdvancedSettingsState,
    onBackClick: () -> Unit = EnsureNeverCalled(),
    onOpenMxtrSettings: () -> Unit = EnsureNeverCalled(),
) {
    setContent {
        AdvancedSettingsView(
            state = state,
            onBackClick = onBackClick,
            onOpenMxtrSettings = onOpenMxtrSettings,
        )
    }
}
