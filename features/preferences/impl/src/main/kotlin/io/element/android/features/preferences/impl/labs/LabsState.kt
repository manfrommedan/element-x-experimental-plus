/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.labs

import io.element.android.libraries.featureflag.ui.model.FeatureUiModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

data class LabsState(
    val sections: ImmutableList<LabsSection>,
    val isApplyingChanges: Boolean,
    val eventSink: (LabsEvent) -> Unit,
) {
    /** Flat view of every feature across sections - kept for tests / legacy. */
    val features: ImmutableList<FeatureUiModel>
        get() = sections.flatMap { it.features }.toImmutableList()
}

data class LabsSection(
    val titleResId: Int,
    val features: ImmutableList<FeatureUiModel>,
)
