/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.event

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.ui.strings.CommonStrings

/**
 * The three real phases of a locally-sending media event, derived from the send state
 * (see [LocalEventSendState.Sending]) rather than from a raw byte threshold.
 */
private enum class UploadPhase { Queued, Uploading, Finalising }

/** Fraction at/above which all bytes are considered uploaded and the send is finalising. */
private const val FINALISING_THRESHOLD = 0.999f

/** Duration used to smooth the coarse byte fraction into a continuous ring fill. */
private const val PROGRESS_ANIMATION_MS = 500

private const val SCRIM_ALPHA = 0.45f
private const val BUTTON_BACKGROUND_ALPHA = 0.6f
private const val TRACK_ALPHA = 0.3f

/**
 * Translucent scrim + phase-aware progress indicator + X cancel button, overlaid on a media
 * event that is still being sent.
 *
 * Real data (matrix-rust-sdk): [progress] is `null` while the event is only QUEUED
 * (LocalEventSendState.Sending.Event) and no figures exist yet; once uploading it carries
 * current/total in coarse "pseudo units" (not raw bytes) where `total` can momentarily be 0 and
 * `current` can briefly exceed `total`, and thumbnail+file are aggregated into one figure.
 *
 * The indicator is chosen from the phase (not from the fraction each frame), so it flips at most
 * twice — queued -> uploading -> finalising — and never per update:
 *  - Queued / no movement yet: indeterminate spinner + "Waiting…" (never a frozen 0% ring).
 *  - Uploading: determinate, smoothed + monotonic ring + an honest "N%" (capped at 99%).
 *  - Finalising (all bytes in, event not yet acked): indeterminate spinner + "Sending…"
 *    (never a static "100%" that reads as already done).
 *
 * Tapping the circle invokes [onCancel]; the caller wires that to Timeline.cancelSend(transactionId).
 */
@Composable
fun MediaUploadOverlay(
    progress: LocalEventSendState.Sending.MediaWithProgress?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = progress?.total ?: 0L
    val current = progress?.progress ?: 0L
    val hasBytes = progress != null && total > 0L
    val rawFraction = if (hasBytes) (current.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f

    // Monotonic clamp: the pseudo-unit stream is coarse and can re-emit a lower value (e.g. the
    // thumbnail -> file two-phase upload). Never let the ring travel backwards within a send. This
    // also provides the hysteresis that keeps the finalising boundary from bouncing.
    var maxFraction by remember { mutableFloatStateOf(0f) }
    if (rawFraction > maxFraction) {
        maxFraction = rawFraction
    }

    // Smooth the (monotonic) fraction so a coarse 0 -> total jump renders as one continuous fill.
    val animatedFraction by animateFloatAsState(
        targetValue = maxFraction,
        animationSpec = tween(durationMillis = PROGRESS_ANIMATION_MS, easing = FastOutSlowInEasing),
        label = "MediaUploadProgress",
    )

    val phase = when {
        progress == null -> UploadPhase.Queued
        total <= 0L -> UploadPhase.Queued
        animatedFraction >= FINALISING_THRESHOLD -> UploadPhase.Finalising
        // A held (current == 0) emit is still "waiting", not a real 0% upload: keep the spinner
        // rather than drawing a frozen, empty determinate ring.
        animatedFraction <= 0f -> UploadPhase.Queued
        else -> UploadPhase.Uploading
    }

    // Honest percentage: shown only while genuinely transferring, floored and capped at 99 so
    // "100%" is never displayed before the bytes are actually done.
    val percent = (animatedFraction * 100).toInt().coerceIn(0, 99)

    val cancelLabel = stringResource(CommonStrings.action_cancel)
    val statusText = when (phase) {
        UploadPhase.Queued -> stringResource(CommonStrings.common_waiting)
        UploadPhase.Uploading -> "$percent%"
        UploadPhase.Finalising -> stringResource(CommonStrings.common_sending)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = BUTTON_BACKGROUND_ALPHA))
                    .clickable(role = Role.Button, onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                when (phase) {
                    UploadPhase.Uploading ->
                        CircularProgressIndicator(
                            progress = { animatedFraction },
                            modifier = Modifier.size(44.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                            trackColor = Color.White.copy(alpha = TRACK_ALPHA),
                        )
                    UploadPhase.Queued,
                    UploadPhase.Finalising ->
                        CircularProgressIndicator(
                            modifier = Modifier.size(44.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                            trackColor = Color.White.copy(alpha = TRACK_ALPHA),
                        )
                }
                Icon(
                    imageVector = CompoundIcons.Close(),
                    contentDescription = cancelLabel,
                    tint = Color.White,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(20.dp),
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = statusText,
                color = Color.White,
                style = ElementTheme.typography.fontBodyXsMedium,
            )
        }
    }
}

internal class MediaWithProgressPreviewParam : PreviewParameterProvider<LocalEventSendState.Sending.MediaWithProgress?> {
    override val values: Sequence<LocalEventSendState.Sending.MediaWithProgress?> = sequenceOf(
        // Queued (Sending.Event -> no progress object).
        null,
        // Uploading, mid.
        LocalEventSendState.Sending.MediaWithProgress(index = 0L, progress = 45L, total = 100L),
        // Finalising (all bytes in, still being sent).
        LocalEventSendState.Sending.MediaWithProgress(index = 0L, progress = 100L, total = 100L),
    )
}

@PreviewsDayNight
@Composable
internal fun MediaUploadOverlayPreview(
    @PreviewParameter(MediaWithProgressPreviewParam::class) progress: LocalEventSendState.Sending.MediaWithProgress?,
) = ElementPreview {
    Box(
        modifier = Modifier
            .size(160.dp)
            .background(Color.DarkGray)
    ) {
        MediaUploadOverlay(
            progress = progress,
            onCancel = {},
        )
    }
}
