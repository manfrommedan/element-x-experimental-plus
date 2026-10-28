/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.user

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.matrix.ui.components.MatrixUserHeader
import io.element.android.libraries.matrix.ui.components.MatrixUserPreviewParam
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.coroutines.launch

@Composable
fun UserPreferences(
    matrixUser: MatrixUser,
    modifier: Modifier = Modifier,
    showCopyMxidButton: Boolean = false,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val toastMessage = stringResource(CommonStrings.common_copied_to_clipboard)
    MatrixUserHeader(
        modifier = modifier,
        matrixUser = matrixUser,
        onCopyMxidClick = if (showCopyMxidButton) {
            {
                coroutineScope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Matrix ID", matrixUser.userId.value)))
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            null
        },
    )
}

@PreviewsDayNight
@Composable
internal fun UserPreferencesPreview(@PreviewParameter(MatrixUserPreviewParam::class) matrixUser: MatrixUser) = ElementPreview {
    UserPreferences(matrixUser, showCopyMxidButton = true)
}
