/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl.roomlist

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.features.home.impl.model.LatestEvent
import io.element.android.features.home.impl.model.RoomListRoomSummary
import io.element.android.features.home.impl.model.aRoomListRoomSummary
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.fullscreenintent.api.FullScreenIntentPermissionsState
import io.element.android.libraries.fullscreenintent.api.aFullScreenIntentPermissionsState
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.push.api.battery.BatteryOptimizationState
import io.element.android.libraries.push.api.battery.aBatteryOptimizationState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableSet

open class RoomListContentStateProvider : PreviewParameterProvider<RoomListContentState> {
    override val values: Sequence<RoomListContentState>
        get() = sequenceOf(
            aRoomsContentState(),
            aRoomsContentState(summaries = persistentListOf()),
            aSkeletonContentState(),
            anEmptyContentState(),
            anEmptyContentState(securityBannerState = SecurityBannerState.SetUpRecovery),
            aRoomsContentState(
                showNewNotificationSoundBanner = true,
            ),
            aRoomsContentState(
                summaries = aRoomListRoomSummaryListWithFavorites(),
                pinFavoritesToTop = true,
            ),
        )
}

internal fun aRoomsContentState(
    securityBannerState: SecurityBannerState = SecurityBannerState.None,
    showNewNotificationSoundBanner: Boolean = false,
    showUnreadCount: Boolean = false,
    summaries: ImmutableList<RoomListRoomSummary> = aRoomListRoomSummaryList(),
    fullScreenIntentPermissionsState: FullScreenIntentPermissionsState = aFullScreenIntentPermissionsState(),
    batteryOptimizationState: BatteryOptimizationState = aBatteryOptimizationState(),
    seenRoomInvites: Set<RoomId> = emptySet(),
    pinFavoritesToTop: Boolean = false,
) = RoomListContentState.Rooms(
    securityBannerState = securityBannerState,
    showNewNotificationSoundBanner = showNewNotificationSoundBanner,
    showUnreadCount = showUnreadCount,
    fullScreenIntentPermissionsState = fullScreenIntentPermissionsState,
    batteryOptimizationState = batteryOptimizationState,
    summaries = summaries,
    seenRoomInvites = seenRoomInvites.toImmutableSet(),
    pinFavoritesToTop = pinFavoritesToTop,
)

internal fun aRoomListRoomSummaryListWithFavorites(): ImmutableList<RoomListRoomSummary> = persistentListOf(
    aRoomListRoomSummary(
        id = "!fav1:domain",
        name = "Alice (favourite)",
        isFavorite = true,
        timestamp = "14:20",
        latestEvent = LatestEvent.Synced("Hey, just checking in"),
        avatarData = AvatarData("!fav1", "A", size = AvatarSize.RoomListItem),
    ),
    aRoomListRoomSummary(
        id = "!fav2:domain",
        name = "Team Chat (favourite)",
        isFavorite = true,
        timestamp = "14:18",
        latestEvent = LatestEvent.Synced("Standup at 10"),
        avatarData = AvatarData("!fav2", "T", size = AvatarSize.RoomListItem),
    ),
    aRoomListRoomSummary(
        id = "!regular1:domain",
        name = "Random Room",
        timestamp = "13:55",
        latestEvent = LatestEvent.Synced("A short message"),
        avatarData = AvatarData("!regular1", "R", size = AvatarSize.RoomListItem),
    ),
    aRoomListRoomSummary(
        id = "!regular2:domain",
        name = "Other Chat",
        timestamp = "12:30",
        latestEvent = LatestEvent.Synced("See you tomorrow"),
        avatarData = AvatarData("!regular2", "O", size = AvatarSize.RoomListItem),
    ),
)

internal fun aSkeletonContentState() = RoomListContentState.Skeleton(16)

internal fun anEmptyContentState(
    securityBannerState: SecurityBannerState = SecurityBannerState.None,
) = RoomListContentState.Empty(
    securityBannerState = securityBannerState,
)
