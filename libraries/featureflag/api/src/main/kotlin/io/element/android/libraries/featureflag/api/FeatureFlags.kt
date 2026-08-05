/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.featureflag.api

import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.core.meta.BuildType

/**
 * To enable or disable a FeatureFlags, change the `defaultValue` value.
 */
enum class FeatureFlags(
    override val key: String,
    override val title: String,
    override val description: String? = null,
    override val defaultValue: (BuildMeta) -> Boolean,
    override val isFinished: Boolean,
    override val isInLabs: Boolean = false,
) : Feature {
    ShowBlockedUsersDetails(
        key = "feature.showBlockedUsersDetails",
        title = "Show blocked users details",
        description = "Show the name and avatar of blocked users in the blocked users list",
        defaultValue = { false },
        isFinished = false,
    ),
    SyncOnPush(
        key = "feature.syncOnPush",
        title = "Sync on push",
        description = "Subscribe to room sync when a push is received",
        defaultValue = { true },
        isFinished = false,
    ),
    OnlySignedDeviceIsolationMode(
        key = "feature.onlySignedDeviceIsolationMode",
        title = "Exclude insecure devices when sending/receiving messages",
        description = "This setting controls how end-to-end encryption (E2E) keys are shared." +
            " Enabling it will prevent the inclusion of devices that have not been explicitly verified by their owners." +
            " You'll have to stop and re-open the app manually for that setting to take effect.",
        defaultValue = { false },
        isFinished = false,
    ),
    Knock(
        key = "feature.knock",
        title = "Ask to join",
        description = "Allow creating rooms which users can request access to.",
        defaultValue = { false },
        isFinished = false,
    ),
    PrintLogsToLogcat(
        key = "feature.print_logs_to_logcat",
        title = "Print logs to logcat",
        description = "Print logs to logcat in addition to log files. Requires an app restart to take effect." +
            "\n\nWARNING: this will make the logs visible in the device logs and may affect performance. " +
            "It's not intended for daily usage in release builds.",
        defaultValue = { buildMeta -> buildMeta.buildType != BuildType.RELEASE },
        // False so it's displayed in the developer options screen
        isFinished = false,
    ),
    SelectableMediaQuality(
        key = "feature.selectable_media_quality",
        title = "Select media quality per upload",
        description = "You can select the media quality for each attachment you upload.",
        defaultValue = { false },
        // False so it's displayed in the developer options screen
        isFinished = false,
    ),
    Threads(
        key = "feature.thread_timeline",
        title = "Threads",
        description = "Renders thread messages as a dedicated timeline. Restarting the app is required for this setting to fully take effect.",
        defaultValue = { false },
        isFinished = false,
        isInLabs = true,
    ),
    MultiAccount(
        key = "feature.multi_account",
        title = "Multi accounts",
        description = "Allow the application to connect to multiple accounts at the same time." +
            "\n\nWARNING: this feature is EXPERIMENTAL and UNSTABLE.",
        defaultValue = { false },
        isFinished = false,
    ),
    QrCodeLogin(
        key = "feature.qr_code_login",
        title = "QR Code Login",
        description = "Allow logging in on other devices using a QR code.",
        defaultValue = { false },
        isFinished = false,
    ),
    AllowBlackTheme(
        key = "feature.allow_black_theme",
        title = "Black theme",
        description = "Allow selecting the black appearance theme for battery saving on OLED.",
        defaultValue = { false },
        isFinished = false,
    ),
    ValidateNetworkWhenSchedulingNotificationFetching(
        key = "feature.validate_network_when_scheduling_notification_fetching",
        title = "Validate internet connectivity when scheduling notification fetching",
        description = "Only fetch events for push notifications when the device has internet connectivity. " +
            "Enabling this can be problematic in air-gapped environments.",
        defaultValue = { true },
        isFinished = false,
    ),
    JumpToUnread(
        key = "feature.jump_to_unread",
        title = "Jump to unread messages",
        description = "Show a button to jump to the read marker, plus a count badge on the scroll-to-bottom button " +
            "when new messages arrive while scrolled away.",
        defaultValue = { false },
        isFinished = false,
    ),
    SlashCommand(
        key = "feature.slash_command",
        title = "Parse slash commands in the message composer",
        description = "Allow parsing slash commands in the message composer and perform action.",
        defaultValue = { false },
        isFinished = false,
    ),
    RoomThreadList(
        key = "feature.room_thread_list",
        title = "Add a list of threads in a room",
        description = "Add a new screen with a list of threads in a room.",
        defaultValue = { false },
        isFinished = false,
    ),
    AutomaticBackPagination(
        key = "feature.automatic_back_pagination",
        title = "Automatic back pagination of rooms",
        description = "Allow the app to automatically back paginate in rooms to pre-fetch older messages in background." +
            "\nRequires an app restart to take effect.",
        defaultValue = { false },
        isFinished = false,
    ),
    PhoneVoiceLayout(
        key = "feature.phone_voice_layout",
        title = "Phone-style calls",
        description = "Switches the call experience to a messenger flow: voice and video buttons in every room (1:1 and group), no lobby preview, and a classic phone-style UI for voice calls." +
            " Disable to fall back to the upstream Element Call experience.",
        defaultValue = { true },
        isFinished = false,
        isInLabs = true,
    ),
    SendMediaAsSeparateMessages(
        key = "feature.send_media_as_separate_messages",
        title = "Send each picture as its own message",
        description = "A batch of attachments leaves as one message per file, which every client can render. " +
            "Takes precedence over the developer \"Send gallery messages\" flag, which packs the batch into a single " +
            "collage that clients without MSC4274 show as plain text.",
        defaultValue = { true },
        isFinished = false,
        isInLabs = true,
    ),
    ShareMxidShortcut(
        key = "feature.share_mxid_shortcut",
        title = "Copy Matrix ID from settings",
        description = "Adds a copy-to-clipboard button next to your Matrix ID in the settings header so you can share it in one tap.",
        defaultValue = { true },
        isFinished = false,
        isInLabs = true,
    ),
    MessageMultiSelect(
        key = "feature.message_multi_select",
        title = "Multi-select messages",
        description = "Long-press a text message to start selecting and drag up or down to sweep a whole range (the list auto-scrolls at the edges). Long-press media for its menu, where \"Select\" also starts selection. Bulk copy, forward and delete up to 30 at once.",
        defaultValue = { false },
        isFinished = false,
        isInLabs = true,
    ),
    FavoritesPinnedToTop(
        key = "feature.favorites_pinned_to_top",
        title = "Pin favourites at the top",
        description = "Always show your favourite rooms above the rest of the chat list, in their own section.",
        defaultValue = { true },
        isFinished = false,
        isInLabs = true,
    ),
    PhoneIncomingCall(
        key = "feature.phone_incoming_call",
        title = "Phone-style incoming calls",
        description = "Show a full-screen ringing call (with ringtone, answerable over the lock screen) when a call comes in, instead of a quiet heads-up notification." +
            " Voice and video calls are labelled distinctly. Disable to fall back to the upstream incoming-call notification.",
        defaultValue = { true },
        isFinished = false,
        isInLabs = true,
    ),
    RoomListCallShortcut(
        key = "feature.room_list_call_shortcut",
        title = "Join calls from the chat list",
        description = "Tap the call icon on a room in the chat list to join its ongoing call right away, like WhatsApp or Telegram.",
        // Temporarily disabled and hidden from Labs: joining a call from the chat list leaves
        // a dangling call membership (the ongoing-call event/indicator never clears). Off by
        // default (canJoinCallFromList stays false) and not shown until the leave/cleanup path
        // is fixed; the implementation is kept in place. Re-enable: defaultValue/isInLabs -> true.
        defaultValue = { false },
        isFinished = false,
        isInLabs = false,
    ),
    AnswerCallOnLockScreen(
        key = "feature.answer_call_on_lock_screen",
        title = "Answer calls without unlocking",
        description = "Answer an incoming call straight from the lock screen, without unlocking the device first, like WhatsApp.",
        defaultValue = { false },
        isFinished = false,
        isInLabs = true,
    ),
    UnreadIndicatorCount(
        key = "feature.unread_indicator_count",
        title = "Unread indicator count",
        description = "Show the number of unread messages on the unread indicator in the room list.",
        defaultValue = { false },
        isFinished = false,
    ),
    SendGalleryMessages(
        key = "feature.send_gallery_messages",
        title = "Send gallery messages",
        description = "Allow sending multiple media items in a single message.",
        defaultValue = { false },
        isFinished = false,
    ),
    UserStatus(
        key = "feature.user_status",
        title = "User status",
        description = "Allow users to set a status (e.g. In a meeting, Away) visible to their contacts.",
        defaultValue = { false },
        isFinished = false,
    ),
    MessageSearch(
        key = "feature.message_search",
        title = "Message search",
        description = "Index messages locally so they can be searched. Older history is backfilled in the background.",
        defaultValue = { false },
        isFinished = false,
    ),
}
