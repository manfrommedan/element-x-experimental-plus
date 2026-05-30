/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.root

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.services.toolbox.api.strings.StringProvider

interface VersionFormatter {
    fun get(): String
}

@ContributesBinding(AppScope::class)
class DefaultVersionFormatter(
    private val stringProvider: StringProvider,
    private val buildMeta: BuildMeta,
) : VersionFormatter {
    override fun get(): String {
        val base = stringProvider.getString(
            CommonStrings.settings_version_number,
            buildMeta.versionName,
            buildMeta.versionCode.toString()
        )
        // versionName already carries the Element X base version and the "-plus" fork marker, so
        // the single version line is enough. The working branch name (buildMeta.gitBranchName) is
        // intentionally not shown - it stays available in BuildMeta for bug reports.
        return "$base\n$MXTR_PROXY_LABEL"
    }

    companion object {
        // Brand line shown under the version in Settings -> About.
        // mxtr = Matrix Transport, our anti-DPI tunnel protocol.
        private const val MXTR_PROXY_LABEL = "mxtrproxy-antidpi"
    }
}
