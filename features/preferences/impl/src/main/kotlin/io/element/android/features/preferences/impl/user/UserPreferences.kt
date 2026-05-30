/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.user

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import io.element.android.libraries.androidutils.system.copyToClipboard
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.matrix.ui.components.MatrixUserHeader
import io.element.android.libraries.matrix.ui.components.MatrixUserProvider
import io.element.android.libraries.ui.strings.CommonStrings

@Composable
fun UserPreferences(
    matrixUser: MatrixUser,
    modifier: Modifier = Modifier,
    showCopyMxidButton: Boolean = false,
) {
    val context = LocalContext.current
    val toastMessage = stringResource(CommonStrings.common_copied_to_clipboard)
    MatrixUserHeader(
        modifier = modifier,
        matrixUser = matrixUser,
        onCopyMxidClick = if (showCopyMxidButton) {
            {
                context.copyToClipboard(text = matrixUser.userId.value, toastMessage = toastMessage)
            }
        } else {
            null
        },
    )
}

@PreviewsDayNight
@Composable
internal fun UserPreferencesPreview(@PreviewParameter(MatrixUserProvider::class) matrixUser: MatrixUser) = ElementPreview {
    UserPreferences(matrixUser, showCopyMxidButton = true)
}
