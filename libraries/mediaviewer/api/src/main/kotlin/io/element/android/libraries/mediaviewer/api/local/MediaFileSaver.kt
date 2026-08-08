/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.api.local

/**
 * Writes an already downloaded file into the device's Downloads folder.
 *
 * The media viewer does this through LocalMediaActions, which also opens and shares files and
 * therefore needs an activity to be around. Saving needs none of that, and callers outside the
 * viewer (the timeline, saving a whole selection at once) have no viewer to borrow it from.
 */
interface MediaFileSaver {
    suspend fun saveInDownloads(localMedia: LocalMedia): Result<Unit>
}
