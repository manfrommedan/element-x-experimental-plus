/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.root

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.test.core.aBuildMeta
import io.element.android.services.toolbox.test.strings.FakeStringProvider
import kotlinx.coroutines.test.runTest
import org.junit.Test

class VersionFormatterTest {
    @Test
    fun `version formatter hides the branch name and shows the Element base version - main`() = runTest {
        val sut = DefaultVersionFormatter(
            stringProvider = FakeStringProvider(defaultResult = VERSION),
            buildMeta = aBuildMeta(
                gitBranchName = "main",
                versionName = "versionName",
                versionCode = 123,
                gitRevision = "1234567890",
            )
        )
        assertThat(sut.get()).isEqualTo("${VERSION}versionName, 123\nmxtrproxy-antidpi")
    }

    @Test
    fun `version formatter hides the branch name and shows the Element base version - experiments branch`() = runTest {
        val sut = DefaultVersionFormatter(
            stringProvider = FakeStringProvider(defaultResult = VERSION),
            buildMeta = aBuildMeta(
                versionName = "versionName",
                versionCode = 123,
                gitBranchName = "experiments",
                gitRevision = "1234567890",
            )
        )
        assertThat(sut.get()).isEqualTo("${VERSION}versionName, 123\nmxtrproxy-antidpi")
    }

    companion object {
        const val VERSION = "version"
    }
}
