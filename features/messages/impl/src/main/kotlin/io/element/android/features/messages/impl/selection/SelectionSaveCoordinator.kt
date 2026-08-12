/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.selection

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.messages.impl.R
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarMessage
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Runs a bulk save and owns its progress for as long as it lasts.
 *
 * Session scoped on purpose: a dozen videos take long enough that walking out of the room in the
 * meantime is a normal thing to do, and a batch abandoned halfway because someone glanced at
 * another conversation would be the worse surprise. Holding the progress here rather than in the
 * presenter's composition is what actually makes that true - keep it in a `remember` and the
 * banner, the cancel button and the guard against a second batch all disappear with the screen
 * while the download carries on unattended.
 */
@Inject
@SingleIn(SessionScope::class)
class SelectionSaveCoordinator(
    private val selectionMediaSaver: SelectionMediaSaver,
    private val snackbarDispatcher: SnackbarDispatcher,
    @SessionCoroutineScope private val sessionCoroutineScope: CoroutineScope,
) {
    private data class Batch(
        val roomId: RoomId,
        val progress: SelectionSaveProgress,
    )

    private val batch = MutableStateFlow<Batch?>(null)
    private var job: Job? = null

    /**
     * Progress of the batch belonging to [roomId], or null. Scoped to the room so the banner shows
     * up where the files were picked and nowhere else.
     */
    fun progressIn(roomId: RoomId): Flow<SelectionSaveProgress?> = batch
        .map { current -> current?.takeIf { it.roomId == roomId }?.progress }
        .distinctUntilChanged()

    /**
     * Starts saving [targets]. Does nothing if a batch is already running, in any room: these are
     * whole-file downloads, and running two sets of them at once only makes both slower.
     */
    fun start(roomId: RoomId, targets: List<SavableMedia>) {
        if (job?.isActive == true || targets.isEmpty()) return
        batch.value = Batch(roomId, SelectionSaveProgress(saved = 0, total = targets.size))
        job = sessionCoroutineScope.launch {
            var saved = 0
            try {
                targets.forEach { target ->
                    selectionMediaSaver.save(target)
                        .onSuccess {
                            saved += 1
                            batch.value = Batch(roomId, SelectionSaveProgress(saved = saved, total = targets.size))
                        }
                        .onFailure {
                            Timber.w(it, "Bulk save: one of ${targets.size} files could not be saved")
                        }
                }
            } finally {
                batch.value = null
            }
            // Three outcomes, not two: saying "error" over a batch where most files did land would
            // send someone looking for files that are already there.
            snackbarDispatcher.post(
                SnackbarMessage(
                    when {
                        // One file or several: the viewer's own wording is about a file, singular,
                        // and reads wrong over a batch of twelve.
                        saved == targets.size && saved == 1 -> CommonStrings.common_file_saved_on_disk_android
                        saved == targets.size -> R.string.screen_messages_selection_saved
                        saved > 0 -> R.string.screen_messages_selection_saved_partly
                        else -> CommonStrings.common_error
                    }
                )
            )
        }
    }

    /**
     * Stops the running batch. Whatever has already been written stays written: stopping a batch is
     * not undoing it, and going round deleting files the user watched arrive would be the greater
     * surprise. No snackbar either - the person pressing cancel knows how it ended.
     */
    fun cancel() {
        job?.cancel()
        job = null
        batch.value = null
    }
}
