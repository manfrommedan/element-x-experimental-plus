/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messagesearch.impl

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import io.element.android.annotations.ContributesNode
import io.element.android.features.messagesearch.api.MessageSearchEntryPoint
import io.element.android.libraries.androidutils.toast.ToastHelper
import io.element.android.libraries.architecture.NodeInputs
import io.element.android.libraries.architecture.callback
import io.element.android.libraries.architecture.inputs
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.search.MessageSearchEventResolver
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.coroutines.launch
import timber.log.Timber

@ContributesNode(SessionScope::class)
@AssistedInject
class MessageSearchNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    presenterFactory: MessageSearchPresenter.Factory,
    private val messageSearchEventResolver: MessageSearchEventResolver,
    private val toastHelper: ToastHelper,
) : Node(buildContext, plugins = plugins) {
    data class Inputs(val roomId: RoomId?) : NodeInputs

    private val presenter = presenterFactory.create(roomId = inputs<Inputs>().roomId)

    @Composable
    override fun View(modifier: Modifier) {
        val state = presenter.present()
        MessageSearchView(
            state = state,
            onResultClick = { result ->
                lifecycleScope.launch {
                    messageSearchEventResolver.resolve(result.roomId, result.eventId)
                        .onSuccess { eventId ->
                            callback<MessageSearchEntryPoint.Callback>().navigateToEvent(result.roomId, eventId)
                        }
                        .onFailure {
                            Timber.e("Could not resolve the selected search result: ${it::class.simpleName}")
                            toastHelper.show(CommonStrings.common_error)
                        }
                }
            },
            onBackClick = ::navigateUp,
            modifier = modifier,
        )
    }
}
