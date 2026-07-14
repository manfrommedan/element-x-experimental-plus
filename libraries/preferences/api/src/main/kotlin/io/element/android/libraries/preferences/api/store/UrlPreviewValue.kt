/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.preferences.api.store

/**
 * Device-local setting controlling when URL previews are shown in the timeline.
 * - [On] shows previews in every room.
 * - [Off] never shows previews.
 * - [UnencryptedOnly] shows previews only in unencrypted rooms.
 */
enum class UrlPreviewValue {
    On,
    Off,
    UnencryptedOnly;

    companion object {
        val DEFAULT = UnencryptedOnly
    }
}

/**
 * Whether a URL preview should be fetched for a message in a room with the given encryption state.
 *
 * Encrypted rooms are treated conservatively: a preview is only fetched when the user explicitly
 * opted into [On], because asking the homeserver to preview a link reveals something about a
 * message that was sent end-to-end encrypted. [UnencryptedOnly] (the default) and an unset value
 * therefore only preview in unencrypted rooms.
 */
fun UrlPreviewValue?.isUrlPreviewEnabled(isEncrypted: Boolean): Boolean = when (this) {
    UrlPreviewValue.On -> true
    UrlPreviewValue.Off -> false
    null, UrlPreviewValue.UnencryptedOnly -> !isEncrypted
}
