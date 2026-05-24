/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.event

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Icon

/**
 * Translucent dim + circular determinate progress + X cancel button,
 * overlaid on the thumbnail of a media event that is currently uploading
 * (LocalEventSendState.Sending.MediaWithProgress).
 *
 * Tapping the X invokes [onCancel]; the caller is responsible for wiring
 * that to Timeline.cancelSend(transactionId).
 */
@Composable
fun MediaUploadOverlay(
    progress: Long,
    total: Long,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction = if (total > 0L) (progress.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.size(44.dp),
                color = Color.White,
                strokeWidth = 2.dp,
                trackColor = Color.White.copy(alpha = 0.3f),
            )
            Icon(
                imageVector = CompoundIcons.Close(),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .padding(10.dp)
                    .size(20.dp),
            )
        }
    }
}
