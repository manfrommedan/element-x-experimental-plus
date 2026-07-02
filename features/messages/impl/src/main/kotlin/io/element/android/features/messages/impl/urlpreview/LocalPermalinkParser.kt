/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.urlpreview

import androidx.compose.runtime.compositionLocalOf
import io.element.android.libraries.matrix.api.permalink.PermalinkParser

/**
 * Exposes the session [PermalinkParser] to the timeline so link previews can authoritatively skip
 * Matrix identifiers (mentions, room links, event permalinks) instead of guessing from the URL
 * shape. Null when no parser is provided (previews, tests), in which case a lightweight shape-based
 * fallback is used.
 */
val LocalPermalinkParser = compositionLocalOf<PermalinkParser?> { null }
