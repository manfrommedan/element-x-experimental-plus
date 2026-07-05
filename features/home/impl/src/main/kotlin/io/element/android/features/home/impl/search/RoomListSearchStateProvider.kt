/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl.search

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.features.home.impl.model.RoomListRoomSummary
import io.element.android.features.home.impl.roomlist.aRoomListRoomSummaryList
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.ui.components.aMatrixUser
import io.element.android.libraries.usersearch.api.UserSearchResult
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

class RoomListSearchStateProvider : PreviewParameterProvider<RoomListSearchState> {
    override val values: Sequence<RoomListSearchState>
        get() = sequenceOf(
            aRoomListSearchState(),
            aRoomListSearchState(
                isSearchActive = true,
                query = "Test",
                results = aRoomListRoomSummaryList()
            ),
            aRoomListSearchState(
                isSearchActive = true,
                query = "Bob",
                results = aRoomListRoomSummaryList(),
                userResults = aUserSearchResultList(),
            ),
        )
}

fun aRoomListSearchState(
    isSearchActive: Boolean = false,
    query: String = "",
    results: ImmutableList<RoomListRoomSummary> = persistentListOf(),
    userResults: ImmutableList<UserSearchResult> = persistentListOf(),
    isSearchingUsers: Boolean = false,
    startDmAction: AsyncAction<RoomId> = AsyncAction.Uninitialized,
    eventSink: (RoomListSearchEvent) -> Unit = { },
) = RoomListSearchState(
    isSearchActive = isSearchActive,
    query = TextFieldState(initialText = query),
    results = results,
    userResults = userResults,
    isSearchingUsers = isSearchingUsers,
    startDmAction = startDmAction,
    eventSink = eventSink,
)

private fun aUserSearchResultList(): ImmutableList<UserSearchResult> = persistentListOf(
    UserSearchResult(aMatrixUser(displayName = "Bob")),
    UserSearchResult(aMatrixUser(id = "@bobby:example.org", displayName = null), isUnresolved = true),
)
