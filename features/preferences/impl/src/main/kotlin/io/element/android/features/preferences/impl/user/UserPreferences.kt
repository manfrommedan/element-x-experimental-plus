/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.user

import android.view.HapticFeedbackConstants
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.PreviewParameter
import io.element.android.libraries.androidutils.system.copyToClipboard
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.utils.snackbar.LocalSnackbarDispatcher
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarMessage
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
    val view = LocalView.current
    val snackbarDispatcher = LocalSnackbarDispatcher.current
    MatrixUserHeader(
        modifier = modifier,
        matrixUser = matrixUser,
        onCopyMxidClick = if (showCopyMxidButton) {
            {
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                context.copyToClipboard(text = matrixUser.userId.value)
                snackbarDispatcher.post(
                    SnackbarMessage(
                        messageResId = CommonStrings.common_copied_to_clipboard,
                        duration = SnackbarDuration.Long,
                    )
                )
            }
        } else null,
    )
}

@PreviewsDayNight
@Composable
internal fun UserPreferencesPreview(@PreviewParameter(MatrixUserProvider::class) matrixUser: MatrixUser) = ElementPreview {
    UserPreferences(matrixUser, showCopyMxidButton = true)
}
