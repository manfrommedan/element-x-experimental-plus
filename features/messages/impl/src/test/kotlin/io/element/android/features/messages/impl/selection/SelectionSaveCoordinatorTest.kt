/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.selection

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.media.MediaSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

private val A_ROOM = RoomId("!a:server.org")
private val ANOTHER_ROOM = RoomId("!b:server.org")

private fun aSavableMedia(filename: String) = SavableMedia(
    source = MediaSource(url = "mxc://server.org/$filename"),
    filename = filename,
    mimeType = "image/jpeg",
)

class SelectionSaveCoordinatorTest {
    // The test scope itself rather than backgroundScope: advanceUntilIdle() does not run work
    // launched in the background scope, and every test here needs the batch to actually get going.
    private fun TestScope.createCoordinator(saver: FakeSelectionMediaSaver) = SelectionSaveCoordinator(
        selectionMediaSaver = saver,
        snackbarDispatcher = SnackbarDispatcher(),
        sessionCoroutineScope = this,
    )

    @Test
    fun `progress outlives the screen that started the batch`() = runTest {
        // The whole point of holding this outside the presenter's composition: hand the flow to a
        // fresh collector, as a re-entered room does, and the batch is still there to be shown.
        val gate = CompletableDeferred<Unit>()
        val saver = FakeSelectionMediaSaver(beforeEachSave = { gate.await() })
        val coordinator = createCoordinator(saver)

        coordinator.start(A_ROOM, listOf(aSavableMedia("one.jpg"), aSavableMedia("two.jpg")))
        // A collector that did not exist when start() was called.
        val seenLater = coordinator.progressIn(A_ROOM).first()

        assertThat(seenLater).isEqualTo(SelectionSaveProgress(saved = 0, total = 2))
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `progress is scoped to the room the files were picked in`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val saver = FakeSelectionMediaSaver(beforeEachSave = { gate.await() })
        val coordinator = createCoordinator(saver)

        coordinator.start(A_ROOM, listOf(aSavableMedia("one.jpg")))

        assertThat(coordinator.progressIn(A_ROOM).first()).isNotNull()
        // Another room must not sprout a banner for a batch that is none of its business.
        assertThat(coordinator.progressIn(ANOTHER_ROOM).first()).isNull()
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a second batch is refused while one is running`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val saver = FakeSelectionMediaSaver(beforeEachSave = { gate.await() })
        val coordinator = createCoordinator(saver)

        coordinator.start(A_ROOM, listOf(aSavableMedia("one.jpg")))
        coordinator.start(A_ROOM, listOf(aSavableMedia("two.jpg"), aSavableMedia("three.jpg")))

        // Still the first batch, untouched: two downloads at once only make both slower.
        assertThat(coordinator.progressIn(A_ROOM).first()?.total).isEqualTo(1)
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(saver.savedFilenames).containsExactly("one.jpg")
    }

    @Test
    fun `cancel stops the batch and clears the progress`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val saver = FakeSelectionMediaSaver(beforeEachSave = { gate.await() })
        val coordinator = createCoordinator(saver)

        coordinator.start(A_ROOM, listOf(aSavableMedia("one.jpg"), aSavableMedia("two.jpg")))
        assertThat(coordinator.progressIn(A_ROOM).first()).isNotNull()

        coordinator.cancel()
        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(coordinator.progressIn(A_ROOM).first()).isNull()
        assertThat(saver.savedFilenames).isEmpty()
    }

    @Test
    fun `cancel frees the coordinator for the next batch`() = runTest {
        // Regression: a cancelled batch that left the job in place would lock bulk save out for the
        // rest of the session, with nothing on screen to explain why the button does nothing.
        val firstGate = CompletableDeferred<Unit>()
        val saver = FakeSelectionMediaSaver(beforeEachSave = { firstGate.await() })
        val coordinator = createCoordinator(saver)

        coordinator.start(A_ROOM, listOf(aSavableMedia("one.jpg")))
        coordinator.cancel()
        firstGate.complete(Unit)
        advanceUntilIdle()

        coordinator.start(A_ROOM, listOf(aSavableMedia("two.jpg")))
        advanceUntilIdle()

        assertThat(saver.savedFilenames).containsExactly("two.jpg")
    }

    @Test
    fun `every file is written and the progress ends up empty`() = runTest {
        val saver = FakeSelectionMediaSaver()
        val coordinator = createCoordinator(saver)

        coordinator.start(A_ROOM, listOf(aSavableMedia("one.jpg"), aSavableMedia("two.jpg")))
        advanceUntilIdle()

        assertThat(saver.savedFilenames).containsExactly("one.jpg", "two.jpg").inOrder()
        assertThat(coordinator.progressIn(A_ROOM).first()).isNull()
    }
}
