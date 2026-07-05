/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.labs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.zacsweers.metro.Inject
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.preferences.impl.R
import io.element.android.features.preferences.impl.model.EnabledFeature
import io.element.android.features.preferences.impl.tasks.ClearCacheUseCase
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.featureflag.ui.model.FeatureUiModel
import io.element.android.services.toolbox.api.strings.StringProvider
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch

@Inject
class LabsPresenter(
    private val stringProvider: StringProvider,
    private val featureFlagService: FeatureFlagService,
    private val clearCacheUseCase: ClearCacheUseCase,
) : Presenter<LabsState> {
    @Composable
    override fun present(): LabsState {
        val coroutineScope = rememberCoroutineScope()
        val enabledFeatures = remember {
            mutableStateListOf<EnabledFeature>()
        }
        LaunchedEffect(Unit) {
            featureFlagService.getAvailableFeatures(isInLabs = true)
                .forEach { feature ->
                    enabledFeatures.add(EnabledFeature(feature, featureFlagService.isFeatureEnabled(feature)))
                }
        }
        var isApplyingChanges by remember { mutableStateOf(false) }
        val featureUiModels = createUiModels(enabledFeatures)
        val sections = remember(featureUiModels) {
            buildSections(enabledFeatures, featureUiModels)
        }

        fun handleEvent(event: LabsEvents) {
            when (event) {
                is LabsEvents.ToggleFeature -> coroutineScope.launch {
                    val featureIndex = enabledFeatures.indexOfFirst { it.feature.key == event.feature.key }.takeIf { it != -1 } ?: return@launch
                    val enabledFeature = enabledFeatures[featureIndex]
                    val feature = enabledFeature.feature
                    val newValue = enabledFeature.isEnabled.not()
                    if (featureFlagService.setFeatureEnabled(feature, newValue)) {
                        enabledFeatures[featureIndex] = enabledFeatures[featureIndex].copy(isEnabled = newValue)
                        when (feature.key) {
                            FeatureFlags.Threads.key -> {
                                // Threads require a cache clear to recreate the event cache
                                clearCacheUseCase()
                                isApplyingChanges = true
                            }
                        }
                    }
                }
            }
        }
        return LabsState(
            sections = sections,
            isApplyingChanges = isApplyingChanges,
            eventSink = ::handleEvent,
        )
    }

    private fun buildSections(
        enabledFeatures: SnapshotStateList<EnabledFeature>,
        uiModels: ImmutableList<FeatureUiModel>,
    ): ImmutableList<LabsSection> {
        val byKey = uiModels.associateBy { it.key }
        // Categorise each feature; anything not categorised falls into the upstream Labs bucket.
        val callsKeys = setOf(
            FeatureFlags.PhoneVoiceLayout.key,
            FeatureFlags.PhoneIncomingCall.key,
            FeatureFlags.RoomListCallShortcut.key,
            FeatureFlags.AnswerCallOnLockScreen.key,
        )
        val ourImprovementsKeys = setOf(
            FeatureFlags.BulkAttachmentsPicker.key,
            FeatureFlags.ShareMxidShortcut.key,
            FeatureFlags.MessageMultiSelect.key,
            FeatureFlags.FavoritesPinnedToTop.key,
            FeatureFlags.FindPeopleInSearch.key,
        )
        val calls = mutableListOf<FeatureUiModel>()
        val ours = mutableListOf<FeatureUiModel>()
        val upstream = mutableListOf<FeatureUiModel>()
        enabledFeatures.forEach { enabled ->
            val model = byKey[enabled.feature.key] ?: return@forEach
            when (enabled.feature.key) {
                in callsKeys -> calls.add(model)
                in ourImprovementsKeys -> ours.add(model)
                else -> upstream.add(model)
            }
        }
        return listOfNotNull(
            upstream.takeIf { it.isNotEmpty() }?.let { LabsSection(R.string.screen_labs_section_upstream, it.toImmutableList()) },
            ours.takeIf { it.isNotEmpty() }?.let { LabsSection(R.string.screen_labs_section_make_element_better, it.toImmutableList()) },
            calls.takeIf { it.isNotEmpty() }?.let { LabsSection(R.string.screen_labs_section_calls, it.toImmutableList()) },
        ).toImmutableList()
    }

    @Composable
    private fun createUiModels(
        enabledFeatures: SnapshotStateList<EnabledFeature>,
    ): ImmutableList<FeatureUiModel> {
        return enabledFeatures.map { enabledFeature ->
            key(enabledFeature.feature.key) {
                val title = when (enabledFeature.feature) {
                    FeatureFlags.Threads -> stringProvider.getString(R.string.screen_labs_enable_threads)
                    FeatureFlags.PhoneVoiceLayout ->
                        stringProvider.getString(R.string.screen_labs_enable_phone_voice_layout)
                    FeatureFlags.BulkAttachmentsPicker ->
                        stringProvider.getString(R.string.screen_labs_enable_bulk_attachments_picker)
                    FeatureFlags.ShareMxidShortcut ->
                        stringProvider.getString(R.string.screen_labs_enable_share_mxid_shortcut)
                    FeatureFlags.MessageMultiSelect ->
                        stringProvider.getString(R.string.screen_labs_enable_message_multi_select)
                    FeatureFlags.FavoritesPinnedToTop ->
                        stringProvider.getString(R.string.screen_labs_enable_favorites_pinned_to_top)
                    FeatureFlags.FindPeopleInSearch ->
                        stringProvider.getString(R.string.screen_labs_enable_find_people_in_search)
                    FeatureFlags.PhoneIncomingCall ->
                        stringProvider.getString(R.string.screen_labs_enable_phone_incoming_call)
                    FeatureFlags.RoomListCallShortcut ->
                        stringProvider.getString(R.string.screen_labs_enable_room_list_call_shortcut)
                    FeatureFlags.AnswerCallOnLockScreen ->
                        stringProvider.getString(R.string.screen_labs_enable_answer_call_on_lock_screen)
                    else -> enabledFeature.feature.title
                }
                val description = when (enabledFeature.feature) {
                    FeatureFlags.Threads -> stringProvider.getString(R.string.screen_labs_enable_threads_description)
                    FeatureFlags.PhoneVoiceLayout ->
                        stringProvider.getString(R.string.screen_labs_enable_phone_voice_layout_description)
                    FeatureFlags.BulkAttachmentsPicker ->
                        stringProvider.getString(R.string.screen_labs_enable_bulk_attachments_picker_description)
                    FeatureFlags.ShareMxidShortcut ->
                        stringProvider.getString(R.string.screen_labs_enable_share_mxid_shortcut_description)
                    FeatureFlags.MessageMultiSelect ->
                        stringProvider.getString(R.string.screen_labs_enable_message_multi_select_description)
                    FeatureFlags.FavoritesPinnedToTop ->
                        stringProvider.getString(R.string.screen_labs_enable_favorites_pinned_to_top_description)
                    FeatureFlags.FindPeopleInSearch ->
                        stringProvider.getString(R.string.screen_labs_enable_find_people_in_search_description)
                    FeatureFlags.PhoneIncomingCall ->
                        stringProvider.getString(R.string.screen_labs_enable_phone_incoming_call_description)
                    FeatureFlags.RoomListCallShortcut ->
                        stringProvider.getString(R.string.screen_labs_enable_room_list_call_shortcut_description)
                    FeatureFlags.AnswerCallOnLockScreen ->
                        stringProvider.getString(R.string.screen_labs_enable_answer_call_on_lock_screen_description)
                    else -> enabledFeature.feature.description
                }
                val icon = when (enabledFeature.feature) {
                    FeatureFlags.Threads -> CompoundIcons.Threads()
                    FeatureFlags.PhoneVoiceLayout -> CompoundIcons.VoiceCall()
                    FeatureFlags.BulkAttachmentsPicker -> CompoundIcons.Image()
                    FeatureFlags.ShareMxidShortcut -> CompoundIcons.Copy()
                    FeatureFlags.MessageMultiSelect -> CompoundIcons.CheckCircle()
                    FeatureFlags.FavoritesPinnedToTop -> CompoundIcons.Favourite()
                    FeatureFlags.FindPeopleInSearch -> CompoundIcons.User()
                    FeatureFlags.PhoneIncomingCall -> CompoundIcons.VoiceCallSolid()
                    FeatureFlags.RoomListCallShortcut -> CompoundIcons.VideoCallSolid()
                    FeatureFlags.AnswerCallOnLockScreen -> CompoundIcons.LockOff()
                    else -> null
                }
                remember(enabledFeature) {
                    FeatureUiModel(
                        key = enabledFeature.feature.key,
                        title = title,
                        description = description,
                        icon = icon?.let(IconSource::Vector),
                        isEnabled = enabledFeature.isEnabled
                    )
                }
            }
        }.toImmutableList()
    }
}
