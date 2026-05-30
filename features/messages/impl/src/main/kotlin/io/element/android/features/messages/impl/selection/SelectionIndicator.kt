/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.selection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Icon

@Composable
fun SelectionIndicator(
    checked: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        // Expose the checkbox role and checked state so TalkBack announces selection changes;
        // the icon itself is decorative (contentDescription = null).
        modifier = modifier
            .size(40.dp)
            .semantics {
                role = Role.Checkbox
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            modifier = Modifier.size(22.dp),
            imageVector = if (checked) CompoundIcons.CheckCircleSolid() else CompoundIcons.Circle(),
            contentDescription = null,
            tint = if (checked) ElementTheme.colors.iconAccentPrimary else ElementTheme.colors.iconTertiary,
        )
    }
}

@PreviewsDayNight
@Composable
internal fun SelectionIndicatorPreview() = ElementPreview {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectionIndicator(checked = false)
        SelectionIndicator(checked = true)
    }
}
