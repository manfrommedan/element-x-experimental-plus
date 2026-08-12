/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.selection

/**
 * Records what was asked for, in order, and can be told which files to fail on.
 *
 * [beforeEachSave] is a suspension point a test can hold open, which is how a batch can be caught
 * mid-flight - without it every file is written in one uninterrupted turn and there is no such
 * thing as "while the save is running" to observe.
 */
class FakeSelectionMediaSaver(
    private val failFor: (SavableMedia) -> Boolean = { false },
    private val beforeEachSave: suspend (SavableMedia) -> Unit = {},
) : SelectionMediaSaver {
    val savedFilenames = mutableListOf<String>()

    override suspend fun save(media: SavableMedia): Result<Unit> {
        beforeEachSave(media)
        if (failFor(media)) return Result.failure(RuntimeException("Save failed"))
        savedFilenames.add(media.filename)
        return Result.success(Unit)
    }
}
