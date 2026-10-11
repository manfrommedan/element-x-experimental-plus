/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.notifications

import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.fullscreenintent.api.FullScreenIntentPermissionsState
import io.element.android.libraries.matrix.api.room.RoomNotificationMode
import io.element.android.libraries.preferences.api.store.NotificationSound
import io.element.android.libraries.pushproviders.api.Distributor
import kotlinx.collections.immutable.ImmutableList

data class NotificationSettingsState(
    val matrixSettings: MatrixSettings,
    val appSettings: AppSettings,
    val changeNotificationSettingAction: AsyncAction<Unit>,
    val currentPushDistributor: AsyncData<Distributor>,
    val availablePushDistributors: ImmutableList<Distributor>,
    val showChangePushProviderDialog: Boolean,
    val fullScreenIntentPermissionsState: FullScreenIntentPermissionsState,
    val messageSound: SoundChannelUiState,
    val callRingtone: SoundChannelUiState,
    val showMessageSoundDialog: Boolean,
    val showCallRingtoneDialog: Boolean,
    /**
     * One-shot trigger for launching the system ringtone picker. Each
     * [NotificationSettingsEvent.LaunchMessageSoundPicker] increments the value;
     * the view watches this in `LaunchedEffect` and calls the launcher whenever it goes above 0.
     * **Always start at 0** in initial state, providers, and previews — a non-zero seed will
     * auto-open the picker on screen entry.
     */
    val pendingMessageSoundPickerLaunch: Int,
    /**
     * One-shot trigger for launching the system ringtone picker from the call ringtone dialog.
     * Same contract as [pendingMessageSoundPickerLaunch]: **always start at 0**, or the picker
     * auto-opens on screen entry.
     */
    val pendingCallRingtonePickerLaunch: Int,
    /**
     * Built-in push server configuration, non-null only when the current push distributor is the
     * app itself. Null hides the whole "Push server" row.
     */
    val selfPushServer: SelfPushServerState?,
    val eventSink: (NotificationSettingsEvent) -> Unit,
) {
    sealed interface MatrixSettings {
        data object Uninitialized : MatrixSettings
        data class Valid(
            val atRoomNotificationsEnabled: Boolean,
            val callNotificationsEnabled: Boolean,
            val inviteForMeNotificationsEnabled: Boolean,
            val defaultGroupNotificationMode: RoomNotificationMode?,
            val defaultOneToOneNotificationMode: RoomNotificationMode?,
        ) : MatrixSettings

        data class Invalid(
            val fixFailed: Boolean
        ) : MatrixSettings
    }

    data class AppSettings(
        val systemNotificationsEnabled: Boolean,
        val appNotificationsEnabled: Boolean,
    )

    /**
     * UI state for one of the two sound rows (message sound, call ringtone). [copyError] is set
     * when the last pick failed to copy into app-private storage; the persisted choice stays put.
     */
    data class SoundChannelUiState(
        val sound: NotificationSound,
        val displayName: String,
        val copyError: Boolean,
    )

    /**
     * UI state for the built-in push server row and its edit dialog. [healthCheck] is the result
     * of the last on-demand check, keyed by [healthCheckServer] so the dialog can ignore results
     * for an URL that is no longer in the text field. [saveFailed] is set when persisting or
     * re-registering after a server change failed; the dialog stays open and shows an error.
     */
    data class SelfPushServerState(
        val customServer: String?,
        val effectiveServer: String,
        val showDialog: Boolean,
        val healthCheck: AsyncData<Unit>,
        val healthCheckServer: String?,
        val saveFailed: Boolean,
    )

    /**
     * Whether the advanced settings should be shown.
     * This is true if the current push distributor is in a failure state or if there are multiple push distributors available.
     */
    val showAdvancedSettings: Boolean = currentPushDistributor.isFailure() || availablePushDistributors.size > 1
}
