/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.api.local.MediaFileSaver

/**
 * Hands saving back to the media viewer's own implementation, so a file saved from the timeline
 * lands in the same place, under the same name, as one saved from the viewer.
 */
@ContributesBinding(AppScope::class)
class DefaultMediaFileSaver(
    private val localMediaActions: LocalMediaActions,
) : MediaFileSaver {
    override suspend fun saveInDownloads(localMedia: LocalMedia): Result<Unit> = localMediaActions.saveOnDisk(localMedia)
}
