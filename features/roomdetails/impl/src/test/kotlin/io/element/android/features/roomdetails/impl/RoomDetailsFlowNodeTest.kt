/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.roomdetails.impl

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.node.node
import com.bumble.appyx.testing.junit4.util.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.element.android.features.call.test.FakeElementCallEntryPoint
import io.element.android.features.knockrequests.test.FakeKnockRequestsListEntryPoint
import io.element.android.features.messages.api.MessagesEntryPoint
import io.element.android.features.messages.test.FakeMessagesEntryPoint
import io.element.android.features.poll.test.history.FakePollHistoryEntryPoint
import io.element.android.features.reportroom.test.FakeReportRoomEntryPoint
import io.element.android.features.rolesandpermissions.test.FakeChangeRoomMemberRolesEntryPoint
import io.element.android.features.rolesandpermissions.test.FakeRolesAndPermissionsEntryPoint
import io.element.android.features.roomdetails.api.RoomDetailsEntryPoint
import io.element.android.features.roomdetailsedit.test.FakeRoomDetailsEditEntryPoint
import io.element.android.features.securityandprivacy.test.FakeSecurityAndPrivacyEntryPoint
import io.element.android.features.verifysession.test.FakeOutgoingVerificationEntryPoint
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.permalink.PermalinkData
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.mediaviewer.api.MediaGalleryEntryPoint
import io.element.android.libraries.mediaviewer.test.FakeMediaGalleryEntryPoint
import io.element.android.libraries.mediaviewer.test.FakeMediaViewerEntryPoint
import io.element.android.services.analytics.test.FakeAnalyticsService
import io.element.android.tests.testutils.lambda.lambdaError
import org.junit.Rule
import org.junit.Test

class RoomDetailsFlowNodeTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val eventId = EventId("\$event:server.org")

    @Test
    fun `forwarding from media gallery starts the forward flow for the selected event`() {
        lateinit var galleryCallback: MediaGalleryEntryPoint.Callback
        val mediaGalleryEntryPoint = object : MediaGalleryEntryPoint {
            override fun createNode(
                parentNode: Node,
                buildContext: BuildContext,
                callback: MediaGalleryEntryPoint.Callback,
            ): Node {
                galleryCallback = callback
                return node(buildContext) {}
            }
        }
        val callback = RecordingRoomDetailsCallback()
        val flowNode = createRoomDetailsFlowNode(
            callback = callback,
            mediaGalleryEntryPoint = mediaGalleryEntryPoint,
        )

        flowNode.resolve(RoomDetailsFlowNode.NavTarget.MediaGallery, BuildContext.root(null))
        galleryCallback.forward(eventId)

        assertThat(callback.forwardedEventIds).containsExactly(eventId)
    }

    @Test
    fun `forwarding from pinned messages starts the forward flow for the selected event`() {
        lateinit var messagesCallback: MessagesEntryPoint.Callback
        val messagesEntryPoint = object : MessagesEntryPoint {
            override fun createNode(
                parentNode: Node,
                buildContext: BuildContext,
                params: MessagesEntryPoint.Params,
                callback: MessagesEntryPoint.Callback,
            ): Node {
                messagesCallback = callback
                return node(buildContext) {}
            }
        }
        val callback = RecordingRoomDetailsCallback()
        val flowNode = createRoomDetailsFlowNode(
            callback = callback,
            messagesEntryPoint = messagesEntryPoint,
        )

        flowNode.resolve(RoomDetailsFlowNode.NavTarget.PinnedMessagesList, BuildContext.root(null))
        messagesCallback.forwardEvent(eventId)

        assertThat(callback.forwardedEventIds).containsExactly(eventId)
    }

    private fun createRoomDetailsFlowNode(
        callback: RoomDetailsEntryPoint.Callback,
        mediaGalleryEntryPoint: MediaGalleryEntryPoint = FakeMediaGalleryEntryPoint(),
        messagesEntryPoint: MessagesEntryPoint = FakeMessagesEntryPoint(),
    ) = RoomDetailsFlowNode(
        buildContext = BuildContext.root(null),
        plugins = listOf(
            RoomDetailsEntryPoint.Params(RoomDetailsEntryPoint.InitialTarget.RoomDetails),
            callback,
        ),
        pollHistoryEntryPoint = FakePollHistoryEntryPoint(),
        elementCallEntryPoint = FakeElementCallEntryPoint(),
        room = FakeJoinedRoom(),
        analyticsService = FakeAnalyticsService(),
        messagesEntryPoint = messagesEntryPoint,
        knockRequestsListEntryPoint = FakeKnockRequestsListEntryPoint(),
        mediaViewerEntryPoint = FakeMediaViewerEntryPoint(),
        mediaGalleryEntryPoint = mediaGalleryEntryPoint,
        outgoingVerificationEntryPoint = FakeOutgoingVerificationEntryPoint(),
        reportRoomEntryPoint = FakeReportRoomEntryPoint(),
        changeRoomMemberRolesEntryPoint = FakeChangeRoomMemberRolesEntryPoint(),
        rolesAndPermissionsEntryPoint = FakeRolesAndPermissionsEntryPoint(),
        securityAndPrivacyEntryPoint = FakeSecurityAndPrivacyEntryPoint(),
        roomDetailsEditEntryPoint = FakeRoomDetailsEditEntryPoint(),
    )

    private class RecordingRoomDetailsCallback : RoomDetailsEntryPoint.Callback {
        val forwardedEventIds = mutableListOf<EventId>()

        override fun onDone() = lambdaError()
        override fun navigateToGlobalNotificationSettings() = lambdaError()
        override fun navigateToDeveloperSettings() = lambdaError()
        override fun navigateToRoom(roomId: RoomId, serverNames: List<String>, clearBackStack: Boolean) = lambdaError()
        override fun handlePermalinkClick(data: PermalinkData, pushToBackstack: Boolean) = lambdaError()
        override fun navigateToMessageSearch() = lambdaError()

        override fun startForwardEventFlow(eventId: EventId) {
            forwardedEventIds.add(eventId)
        }
    }
}
