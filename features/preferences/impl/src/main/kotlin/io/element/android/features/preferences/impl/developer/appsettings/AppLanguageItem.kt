/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.developer.appsettings

import androidx.compose.runtime.Composable
import androidx.core.os.LocaleListCompat
import io.element.android.libraries.designsystem.components.preferences.DropdownOption

/**
 * Fork-only developer option: override the in-app language at runtime so English
 * screenshots can be captured on a device whose system locale cannot be changed.
 *
 * Backed directly by [androidx.appcompat.app.AppCompatDelegate.setApplicationLocales],
 * which persists the choice and re-applies it on the next launch (natively on Android 13+,
 * via the appcompat locale service on older versions).
 */
enum class AppLanguageItem(val localeTag: String?) : DropdownOption {
    System(null) {
        @Composable
        override fun getText(): String = "System default"
    },
    English("en") {
        @Composable
        override fun getText(): String = "English"
    },
    Russian("ru") {
        @Composable
        override fun getText(): String = "Русский"
    };

    companion object {
        fun fromLocales(locales: LocaleListCompat): AppLanguageItem {
            val tag = locales.takeUnless { it.isEmpty }?.get(0)?.language
            return entries.firstOrNull { it.localeTag == tag } ?: System
        }
    }
}
