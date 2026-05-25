/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.event

import coil3.compose.AsyncImagePainter

/**
 * Decide whether the wifi-only "tap to download" overlay should replace a media
 * thumbnail in the timeline. The overlay is shown ONLY when the user is on
 * mobile data, hasn't opted in, AND Coil has failed to satisfy the request from
 * its memory/disk cache - i.e. the only way forward would be a network fetch
 * we just told it to skip. Cached thumbnails (Loading on first composition
 * then Success once the cache lookup resolves) keep rendering normally.
 *
 * Pre-fix behaviour gated on `shouldLoad` alone, which hid every previously
 * downloaded image behind the overlay when the user toggled off wifi-only.
 */
internal fun shouldShowTapToDownload(
    networkAllowed: Boolean,
    painterState: AsyncImagePainter.State,
): Boolean = !networkAllowed && painterState is AsyncImagePainter.State.Error
