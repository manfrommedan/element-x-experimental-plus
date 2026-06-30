/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.urlpreview

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf

/**
 * Holds the URL of the YouTube preview that is currently playing inline, so at most one video plays
 * at a time across the timeline (like WhatsApp and Telegram). Starting a card writes its URL here,
 * which makes any previously playing card stop and release its player.
 *
 * Null when no coordinator is provided (for example previews or the pinned messages list), in which
 * case each card falls back to its own local play state.
 */
val LocalActiveYouTubePlayer = compositionLocalOf<MutableState<String?>?> { null }
