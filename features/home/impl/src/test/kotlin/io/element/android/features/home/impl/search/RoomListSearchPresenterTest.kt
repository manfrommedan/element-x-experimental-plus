/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl.search

import com.google.common.truth.Truth.assertThat
import io.element.android.features.home.impl.datasource.aRoomListRoomSummaryFactory
import io.element.android.features.invitepeople.test.FakeStartDMAction
import io.element.android.features.startchat.api.ConfirmingStartDmWithMatrixUser
import io.element.android.features.startchat.api.StartDMAction
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.dateformatter.test.FakeDateFormatter
import io.element.android.libraries.eventformatter.test.FakeRoomLatestEventFormatter
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.matrix.api.roomlist.RoomListFilter
import io.element.android.libraries.matrix.api.roomlist.RoomListService
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.test.room.aRoomSummary
import io.element.android.libraries.matrix.test.roomlist.FakeDynamicRoomList
import io.element.android.libraries.matrix.test.roomlist.FakeRoomListService
import io.element.android.libraries.usersearch.api.UserRepository
import io.element.android.libraries.usersearch.api.UserSearchResult
import io.element.android.libraries.usersearch.api.UserSearchResultState
import io.element.android.libraries.usersearch.test.FakeUserRepository
import io.element.android.tests.testutils.consumeItemsUntilPredicate
import io.element.android.tests.testutils.lambda.assert
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.test
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RoomListSearchPresenterTest {
    @Test
    fun `present - initial state`() = runTest {
        val presenter = createRoomListSearchPresenter()
        presenter.test {
            awaitItem().let { state ->
                assertThat(state.isSearchActive).isFalse()
                assertThat(state.query.text.toString()).isEmpty()
                assertThat(state.results).isEmpty()
                assertThat(state.userResults).isEmpty()
                assertThat(state.isSearchingUsers).isFalse()
                assertThat(state.startDmAction).isEqualTo(AsyncAction.Uninitialized)
            }
        }
    }

    @Test
    fun `present - toggle search visibility`() = runTest {
        val presenter = createRoomListSearchPresenter()
        presenter.test {
            awaitItem().let { state ->
                assertThat(state.isSearchActive).isFalse()
                state.eventSink(RoomListSearchEvent.ToggleSearchVisibility)
            }
            awaitItem().let { state ->
                assertThat(state.isSearchActive).isTrue()
                state.eventSink(RoomListSearchEvent.ToggleSearchVisibility)
            }
            awaitItem().let { state ->
                assertThat(state.isSearchActive).isFalse()
            }
        }
    }

    @Test
    fun `present - query search changes`() = runTest {
        val roomList = FakeDynamicRoomList()
        val roomListService = FakeRoomListService(
            createRoomListLambda = { roomList }
        )
        val presenter = createRoomListSearchPresenter(roomListService)
        presenter.test {
            awaitItem().let { state ->
                assertThat(
                    roomList.currentFilter.value
                ).isEqualTo(
                    RoomListFilter.None
                )
                state.query.edit { append("Search") }
            }
            awaitItem().let { state ->
                assertThat(state.query.text).isEqualTo("Search")
                assertThat(
                    roomList.currentFilter.value
                ).isEqualTo(
                    RoomListFilter.NormalizedMatchRoomName("Search")
                )
                state.eventSink(RoomListSearchEvent.ClearQuery)
            }
            awaitItem().let { state ->
                assertThat(state.query.text.toString()).isEmpty()
                assertThat(
                    roomList.currentFilter.value
                ).isEqualTo(
                    RoomListFilter.None
                )
            }
        }
    }

    @Test
    fun `present - room list changes`() = runTest {
        val roomList = FakeDynamicRoomList()
        val roomListService = FakeRoomListService(
            createRoomListLambda = { roomList }
        )
        val presenter = createRoomListSearchPresenter(roomListService)
        presenter.test {
            awaitItem().let { state ->
                assertThat(state.results).isEmpty()
            }
            roomList.summaries.emit(
                listOf(aRoomSummary())
            )
            awaitItem().let { state ->
                assertThat(state.results).hasSize(1)
            }
            roomList.summaries.emit(emptyList())
            awaitItem().let { state ->
                assertThat(state.results).isEmpty()
            }
        }
    }

    @Test
    fun `present - UpdateVisibleRange triggers pagination when near end`() = runTest {
        val loadMoreLambda = lambdaRecorder<Unit> { }
        val roomList = FakeDynamicRoomList(loadMoreLambda = loadMoreLambda)
        val roomListService = FakeRoomListService(
            createRoomListLambda = { roomList }
        )
        val presenter = createRoomListSearchPresenter(roomListService)
        presenter.test {
            val initialState = awaitItem()
            // Post some rooms to simulate loaded content
            val rooms = (1..10).map { aRoomSummary() }
            roomList.summaries.emit(rooms)
            skipItems(1)

            // UpdateVisibleRange near end should trigger loadMore
            initialState.eventSink(RoomListSearchEvent.UpdateVisibleRange(IntRange(0, 9)))
            // Give time for the coroutine to complete
            testScheduler.advanceUntilIdle()

            assert(loadMoreLambda).isCalledOnce()
        }
    }

    @Test
    fun `present - people are not searched when the feature is disabled`() = runTest {
        val userRepository = FakeUserRepository()
        val presenter = createRoomListSearchPresenter(
            userRepository = userRepository,
            featureFlagService = FakeFeatureFlagService(
                initialState = mapOf(FeatureFlags.FindPeopleInSearch.key to false),
            ),
        )
        presenter.test {
            val initialState = awaitItem()
            initialState.query.edit { append("alice") }
            testScheduler.advanceUntilIdle()
            // The user directory is never queried and no people are surfaced.
            assertThat(userRepository.providedQuery).isNull()
            val state = consumeItemsUntilPredicate { it.query.text.toString() == "alice" }.last()
            assertThat(state.userResults).isEmpty()
        }
    }

    @Test
    fun `present - people search results are surfaced when the feature is enabled`() = runTest {
        val userRepository = FakeUserRepository()
        val presenter = createRoomListSearchPresenter(
            userRepository = userRepository,
            featureFlagService = FakeFeatureFlagService(
                initialState = mapOf(FeatureFlags.FindPeopleInSearch.key to true),
            ),
        )
        presenter.test {
            val initialState = awaitItem()
            initialState.query.edit { append("alice") }
            // Let the query propagate and the directory collector subscribe before emitting.
            testScheduler.advanceUntilIdle()
            assertThat(userRepository.providedQuery).isEqualTo("alice")

            userRepository.emitState(
                UserSearchResultState(
                    results = listOf(UserSearchResult(MatrixUser(A_USER_ID))),
                    isSearching = false,
                )
            )
            val state = consumeItemsUntilPredicate { it.userResults.isNotEmpty() }.last()
            assertThat(state.userResults).hasSize(1)
            assertThat(state.userResults.first().matrixUser.userId).isEqualTo(A_USER_ID)
            assertThat(state.isSearchingUsers).isFalse()
        }
    }

    @Test
    fun `present - selecting a user confirms then opens a direct message`() = runTest {
        val startDMAction = FakeStartDMAction(
            executeResult = { _, createIfDmDoesNotExist, actionState ->
                actionState.value = if (createIfDmDoesNotExist) {
                    AsyncAction.Success(A_ROOM_ID)
                } else {
                    ConfirmingStartDmWithMatrixUser(MatrixUser(A_USER_ID), isUserIdentityUnknown = false)
                }
            }
        )
        val presenter = createRoomListSearchPresenter(startDMAction = startDMAction)
        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.startDmAction).isEqualTo(AsyncAction.Uninitialized)
            // First tap: there is no existing DM, so a confirmation is requested.
            initialState.eventSink(RoomListSearchEvent.StartDM(MatrixUser(A_USER_ID)))
            val confirmingState = consumeItemsUntilPredicate { it.startDmAction is AsyncAction.Confirming }.last()
            assertThat(confirmingState.startDmAction).isInstanceOf(ConfirmingStartDmWithMatrixUser::class.java)
            // Confirming re-runs the action, this time creating the DM.
            confirmingState.eventSink(RoomListSearchEvent.StartDM(MatrixUser(A_USER_ID)))
            val successState = consumeItemsUntilPredicate { it.startDmAction is AsyncAction.Success }.last()
            assertThat((successState.startDmAction as AsyncAction.Success).data).isEqualTo(A_ROOM_ID)
        }
    }

    @Test
    fun `present - cancelling a pending direct message resets the action`() = runTest {
        val startDMAction = FakeStartDMAction(
            executeResult = { _, _, actionState ->
                actionState.value = ConfirmingStartDmWithMatrixUser(MatrixUser(A_USER_ID), isUserIdentityUnknown = false)
            }
        )
        val presenter = createRoomListSearchPresenter(startDMAction = startDMAction)
        presenter.test {
            val initialState = awaitItem()
            initialState.eventSink(RoomListSearchEvent.StartDM(MatrixUser(A_USER_ID)))
            val confirmingState = consumeItemsUntilPredicate { it.startDmAction is AsyncAction.Confirming }.last()
            confirmingState.eventSink(RoomListSearchEvent.CancelStartDM)
            val resetState = consumeItemsUntilPredicate { it.startDmAction == AsyncAction.Uninitialized }.last()
            assertThat(resetState.startDmAction).isEqualTo(AsyncAction.Uninitialized)
        }
    }

    @Test
    fun `present - people loader reflects the directory search progress`() = runTest {
        val userRepository = FakeUserRepository()
        val presenter = createRoomListSearchPresenter(
            userRepository = userRepository,
            featureFlagService = FakeFeatureFlagService(
                initialState = mapOf(FeatureFlags.FindPeopleInSearch.key to true),
            ),
        )
        presenter.test {
            val initialState = awaitItem()
            initialState.query.edit { append("alice") }
            testScheduler.advanceUntilIdle()
            // Directory search in progress with no results yet -> loader on.
            userRepository.emitState(UserSearchResultState(results = emptyList(), isSearching = true))
            val searching = consumeItemsUntilPredicate { it.isSearchingUsers }.last()
            assertThat(searching.isSearchingUsers).isTrue()
            assertThat(searching.userResults).isEmpty()
            // Results arrive -> loader off.
            userRepository.emitState(
                UserSearchResultState(results = listOf(UserSearchResult(MatrixUser(A_USER_ID))), isSearching = false)
            )
            val done = consumeItemsUntilPredicate { !it.isSearchingUsers && it.userResults.isNotEmpty() }.last()
            assertThat(done.isSearchingUsers).isFalse()
            assertThat(done.userResults).hasSize(1)
        }
    }

    @Test
    fun `present - clearing the query drops the people results`() = runTest {
        val userRepository = FakeUserRepository()
        val presenter = createRoomListSearchPresenter(
            userRepository = userRepository,
            featureFlagService = FakeFeatureFlagService(
                initialState = mapOf(FeatureFlags.FindPeopleInSearch.key to true),
            ),
        )
        presenter.test {
            val initialState = awaitItem()
            initialState.query.edit { append("alice") }
            testScheduler.advanceUntilIdle()
            userRepository.emitState(
                UserSearchResultState(results = listOf(UserSearchResult(MatrixUser(A_USER_ID))), isSearching = false)
            )
            consumeItemsUntilPredicate { it.userResults.isNotEmpty() }
            // Clearing the query empties the people section again.
            initialState.eventSink(RoomListSearchEvent.ClearQuery)
            val cleared = consumeItemsUntilPredicate { it.query.text.isEmpty() && it.userResults.isEmpty() }.last()
            assertThat(cleared.userResults).isEmpty()
            assertThat(cleared.isSearchingUsers).isFalse()
        }
    }

    @Test
    fun `present - a failed direct message surfaces then dismisses the error`() = runTest {
        val startDMAction = FakeStartDMAction(
            executeResult = { _, _, actionState ->
                actionState.value = AsyncAction.Failure(RuntimeException("boom"))
            }
        )
        val presenter = createRoomListSearchPresenter(startDMAction = startDMAction)
        presenter.test {
            val initialState = awaitItem()
            initialState.eventSink(RoomListSearchEvent.StartDM(MatrixUser(A_USER_ID)))
            val failed = consumeItemsUntilPredicate { it.startDmAction is AsyncAction.Failure }.last()
            assertThat(failed.startDmAction).isInstanceOf(AsyncAction.Failure::class.java)
            failed.eventSink(RoomListSearchEvent.CancelStartDM)
            val reset = consumeItemsUntilPredicate { it.startDmAction == AsyncAction.Uninitialized }.last()
            assertThat(reset.startDmAction).isEqualTo(AsyncAction.Uninitialized)
        }
    }
}

fun TestScope.createRoomListSearchPresenter(
    roomListService: RoomListService = FakeRoomListService(),
    userRepository: UserRepository = FakeUserRepository(),
    startDMAction: StartDMAction = FakeStartDMAction(),
    featureFlagService: FeatureFlagService = FakeFeatureFlagService(
        initialState = mapOf(FeatureFlags.FindPeopleInSearch.key to false),
    ),
): RoomListSearchPresenter {
    return RoomListSearchPresenter(
        dataSourceFactory = object : RoomListSearchDataSource.Factory {
            override fun create(coroutineScope: CoroutineScope): RoomListSearchDataSource {
                return RoomListSearchDataSource(
                    roomListService = roomListService,
                    roomSummaryFactory = aRoomListRoomSummaryFactory(
                        dateFormatter = FakeDateFormatter(),
                        roomLatestEventFormatter = FakeRoomLatestEventFormatter(),
                    ),
                    coroutineDispatchers = testCoroutineDispatchers(),
                    coroutineScope = coroutineScope,
                )
            }
        },
        userRepository = userRepository,
        startDMAction = startDMAction,
        featureFlagService = featureFlagService,
    )
}
