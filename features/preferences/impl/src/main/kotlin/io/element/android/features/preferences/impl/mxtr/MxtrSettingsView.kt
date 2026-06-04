/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.mxtr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.preferences.impl.R
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.OutlinedButton
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Switch
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.matrix.impl.mxtr.MxtrConfig
import io.element.android.libraries.matrix.impl.mxtr.MxtrPreferencesStore
import io.element.android.libraries.matrix.impl.mxtr.MxtrShareString
import io.element.android.libraries.matrix.impl.mxtr.MxtrStats
import io.element.android.libraries.matrix.impl.mxtr.MxtrStatsSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MxtrSettingsView(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { MxtrPreferencesStore(context.applicationContext) }
    val snapshot = remember(context) { store.snapshotBlocking() }

    var enabled by remember { mutableStateOf(snapshot.enabled) }
    var shareString by remember { mutableStateOf(snapshot.data?.toShareString().orEmpty()) }
    var parseValid by remember { mutableStateOf(MxtrShareString.parse(shareString) != null) }
    var showRestartHint by remember { mutableStateOf(false) }
    var diagExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(shareString) {
        parseValid = MxtrShareString.parse(shareString) != null
    }

    var snap by remember { mutableStateOf(MxtrStats.snapshot()) }
    LaunchedEffect(Unit) {
        while (true) {
            snap = MxtrStats.snapshot()
            delay(1000)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_mxtr_settings_title), style = ElementTheme.typography.fontHeadingMdBold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(CompoundIcons.ArrowLeft(), contentDescription = stringResource(R.string.screen_mxtr_settings_back))
                    }
                },
            )
        },
        containerColor = ElementTheme.colors.bgCanvasDefault,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCard(snap = snap, enabled = enabled)

            EnableCard(enabled = enabled, onToggle = { enabled = it })

            ConfigCard(
                shareString = shareString,
                onShareStringChange = { shareString = it },
                parseValid = parseValid,
                onCopy = { if (parseValid) copyToClipboard(context, shareString) },
                onShare = { if (parseValid) shareText(context, shareString) },
            )

            ActionsRow(
                parseValid = parseValid,
                onApply = {
                    if (parseValid) {
                        scope.launch {
                            // HI-04: write share-string before enabled. If
                            // setEnabled succeeds and a later setShareString
                            // fails (process death between), the proxy would
                            // come up "on" with the stale prior PSK.
                            store.setShareString(shareString)
                            store.setEnabled(enabled)
                            showRestartHint = true
                            // Auto-apply: relaunch the process so Application.onCreate
                            // restarts the mxtr proxy daemon with the new state. A soft
                            // (session) restart would leave the daemon unstarted on enable.
                            restartApp(context)
                        }
                    }
                },
                onReset = {
                    val wasEnabled = enabled
                    scope.launch {
                        store.clearShareString()
                        store.setEnabled(false)
                        // Reset commits "off". If the proxy was live, relaunch so the
                        // running daemon actually stops - it only re-reads config at
                        // process start, like the Apply path above.
                        if (wasEnabled) restartApp(context)
                    }
                    shareString = ""
                    enabled = false
                    parseValid = false
                },
            )

            AnimatedVisibility(visible = showRestartHint, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                RestartHintCard()
            }

            DiagnosticsCard(
                snap = snap,
                sni = MxtrShareString.parse(shareString)?.sni,
                expanded = diagExpanded,
                onToggleExpand = { diagExpanded = !diagExpanded }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusCard(snap: MxtrStatsSnapshot, enabled: Boolean) {
    val statusText = when {
        !enabled -> stringResource(R.string.screen_mxtr_settings_status_off)
        snap.acceptLoopAlive -> stringResource(R.string.screen_mxtr_settings_status_connected)
        else -> stringResource(R.string.screen_mxtr_settings_status_starting)
    }
    val color = when {
        !enabled -> ElementTheme.colors.textSecondary
        snap.acceptLoopAlive -> ElementTheme.colors.iconSuccessPrimary
        else -> ElementTheme.colors.iconTertiary
    }
    PremiumCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CompoundIcons.LockSolid(), contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("mxtr-proxy", style = ElementTheme.typography.fontBodyLgMedium, color = ElementTheme.colors.textPrimary)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(color),
                    )
                    Text(statusText, style = ElementTheme.typography.fontBodyMdRegular, color = ElementTheme.colors.textSecondary)
                }
            }
            if (enabled && snap.currentServer != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        stringResource(R.string.screen_mxtr_settings_server),
                        style = ElementTheme.typography.fontBodyXsRegular,
                        color = ElementTheme.colors.textSecondary
                    )
                    Text(
                        snap.currentServer ?: "—",
                        style = ElementTheme.typography.fontBodySmMedium,
                        color = ElementTheme.colors.textPrimary,
                    )
                }
            }
        }
        if (enabled && snap.acceptLoopAlive) {
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = ElementTheme.colors.borderDisabled)
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                MetricColumn(label = stringResource(R.string.screen_mxtr_settings_metric_active), value = snap.active.toString())
                MetricColumn(label = "↑", value = formatBytes(snap.bytesUp))
                MetricColumn(label = "↓", value = formatBytes(snap.bytesDown))
            }
        }
    }
}

@Composable
private fun MetricColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = ElementTheme.typography.fontHeadingSmMedium, color = ElementTheme.colors.textPrimary)
        Text(label, style = ElementTheme.typography.fontBodyXsRegular, color = ElementTheme.colors.textSecondary)
    }
}

@Composable
private fun EnableCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    PremiumCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.screen_mxtr_settings_use_proxy),
                    style = ElementTheme.typography.fontBodyLgMedium,
                    color = ElementTheme.colors.textPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    stringResource(R.string.screen_mxtr_settings_use_proxy_description),
                    style = ElementTheme.typography.fontBodySmRegular,
                    color = ElementTheme.colors.textSecondary,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigCard(
    shareString: String,
    onShareStringChange: (String) -> Unit,
    parseValid: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    PremiumCard {
        Text(
            stringResource(R.string.screen_mxtr_settings_connection_string),
            style = ElementTheme.typography.fontBodyLgMedium,
            color = ElementTheme.colors.textPrimary
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = shareString,
            onValueChange = onShareStringChange,
            placeholder = { Text("mxtr://<psk>@<ip>:<port>?sni=<hostname>") },
            isError = shareString.isNotEmpty() && !parseValid,
            textStyle = ElementTheme.typography.fontBodySmMedium.copy(fontFamily = FontFamily.Monospace),
            supportingText = {
                val parsed = MxtrShareString.parse(shareString)
                val emptyHint = stringResource(R.string.screen_mxtr_settings_connection_string_hint)
                val serverPrefix = stringResource(R.string.screen_mxtr_settings_server_summary, parsed?.host.orEmpty(), parsed?.port ?: 0)
                val formatError = stringResource(R.string.screen_mxtr_settings_format_error)
                val (text, isErr) = when {
                    shareString.isEmpty() -> emptyHint to false
                    parsed != null -> buildString {
                        append(serverPrefix)
                        if (!parsed.sni.isNullOrEmpty()) append(", SNI=").append(parsed.sni)
                    } to false
                    else -> formatError to true
                }
                Text(
                    text,
                    color = if (isErr) ElementTheme.colors.textCriticalPrimary else ElementTheme.colors.textSecondary,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolButton(
                icon = CompoundIcons.Copy(),
                label = stringResource(R.string.screen_mxtr_settings_action_copy),
                onClick = onCopy,
                modifier = Modifier.weight(1f),
            )
            ToolButton(
                icon = CompoundIcons.ShareAndroid(),
                label = stringResource(R.string.screen_mxtr_settings_action_share),
                onClick = onShare,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ElementTheme.colors.bgSubtleSecondary)
            .border(1.dp, ElementTheme.colors.borderDisabled, RoundedCornerShape(12.dp))
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.material3.IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = label,
                tint = ElementTheme.colors.iconPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, style = ElementTheme.typography.fontBodyXsMedium, color = ElementTheme.colors.textSecondary)
    }
}

@Composable
private fun ActionsRow(parseValid: Boolean, onApply: () -> Unit, onReset: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            text = stringResource(R.string.screen_mxtr_settings_action_apply),
            onClick = onApply,
            enabled = parseValid,
            modifier = Modifier.weight(2f),
        )
        OutlinedButton(
            text = stringResource(R.string.screen_mxtr_settings_action_reset),
            onClick = onReset,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RestartHintCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ElementTheme.colors.bgSuccessSubtle)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(CompoundIcons.CheckCircleSolid(), contentDescription = null, tint = ElementTheme.colors.iconSuccessPrimary, modifier = Modifier.size(20.dp))
        Text(
            stringResource(R.string.screen_mxtr_settings_restart_hint),
            style = ElementTheme.typography.fontBodyMdMedium,
            color = ElementTheme.colors.textSuccessPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DiagnosticsCard(
    snap: MxtrStatsSnapshot,
    sni: String?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    val rotation by animateFloatAsState(targetValue = if (expanded) 90f else 0f, animationSpec = tween(200), label = "chevron")
    PremiumCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.screen_mxtr_settings_diagnostics),
                    style = ElementTheme.typography.fontBodyLgMedium,
                    color = ElementTheme.colors.textPrimary
                )
                if (!expanded) {
                    Text(
                        stringResource(
                            R.string.screen_mxtr_settings_diagnostics_summary,
                            snap.total,
                            snap.failed,
                            snap.acceptLoopRestarts,
                        ),
                        style = ElementTheme.typography.fontBodySmRegular,
                        color = ElementTheme.colors.textSecondary,
                    )
                }
            }
            IconButton(onClick = onToggleExpand) {
                Box(modifier = Modifier.size(24.dp)) {
                    androidx.compose.material3.Icon(
                        imageVector = CompoundIcons.ChevronRight(),
                        contentDescription = if (expanded) {
                            stringResource(R.string.screen_mxtr_settings_collapse)
                        } else {
                            stringResource(R.string.screen_mxtr_settings_expand)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(0.dp)
                            .graphicsRotate(rotation),
                        tint = ElementTheme.colors.iconSecondary,
                    )
                }
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HorizontalDivider(color = ElementTheme.colors.borderDisabled)
                Spacer(modifier = Modifier.height(4.dp))
                DiagRow(
                    stringResource(R.string.screen_mxtr_settings_diag_accept_status),
                    if (snap.acceptLoopAlive) {
                        stringResource(R.string.screen_mxtr_settings_diag_listening, MxtrConfig.activeLocalPort())
                    } else {
                        stringResource(R.string.screen_mxtr_settings_diag_not_started)
                    },
                )
                DiagRow(stringResource(R.string.screen_mxtr_settings_diag_restarts), snap.acceptLoopRestarts.toString())
                DiagRow(stringResource(R.string.screen_mxtr_settings_server), snap.currentServer ?: "—")
                if (!sni.isNullOrEmpty()) DiagRow(stringResource(R.string.screen_mxtr_settings_diag_sni_sent), sni)
                DiagRow(stringResource(R.string.screen_mxtr_settings_metric_active), snap.active.toString())
                DiagRow(stringResource(R.string.screen_mxtr_settings_diag_total_connections), snap.total.toString())
                DiagRow(stringResource(R.string.screen_mxtr_settings_diag_succeeded), snap.succeeded.toString())
                DiagRow(stringResource(R.string.screen_mxtr_settings_diag_failed), snap.failed.toString())
                DiagRow(stringResource(R.string.screen_mxtr_settings_diag_sent), formatBytes(snap.bytesUp))
                DiagRow(stringResource(R.string.screen_mxtr_settings_diag_received), formatBytes(snap.bytesDown))

                val nonzeroErrs = snap.errorsByKind.filterValues { it > 0 }
                if (nonzeroErrs.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.screen_mxtr_settings_errors_by_type),
                        style = ElementTheme.typography.fontBodyMdMedium,
                        color = ElementTheme.colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    nonzeroErrs.forEach { (kind, count) -> DiagRow(kind.name, count.toString(), isError = true) }
                }
                if (snap.recentErrors.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.screen_mxtr_settings_recent_errors),
                        style = ElementTheme.typography.fontBodyMdMedium,
                        color = ElementTheme.colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    snap.recentErrors.take(5).forEach { ev ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(ElementTheme.colors.bgCriticalSubtle)
                                .padding(8.dp),
                        ) {
                            Text(
                                "${ev.kind.name} → ${ev.target}",
                                style = ElementTheme.typography.fontBodySmMedium,
                                color = ElementTheme.colors.textCriticalPrimary,
                            )
                            Text(
                                ev.message,
                                style = ElementTheme.typography.fontBodyXsRegular,
                                color = ElementTheme.colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String, isError: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = ElementTheme.typography.fontBodyMdRegular, color = ElementTheme.colors.textSecondary)
        Text(
            value,
            style = ElementTheme.typography.fontBodyMdMedium,
            color = if (isError) ElementTheme.colors.textCriticalPrimary else ElementTheme.colors.textPrimary,
        )
    }
}

@Composable
private fun PremiumCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ElementTheme.colors.bgSubtleSecondary)
            .border(1.dp, ElementTheme.colors.borderDisabled, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        content()
    }
}

private fun Modifier.graphicsRotate(deg: Float): Modifier = this.then(
    Modifier.graphicsLayer { rotationZ = deg }
)

@Composable
private fun formatBytes(n: Long): String = when {
    n < 1024 -> stringResource(R.string.screen_mxtr_settings_unit_bytes, n.toString())
    n < 1024 * 1024 -> stringResource(R.string.screen_mxtr_settings_unit_kib, (n / 1024).toString())
    n < 1024L * 1024 * 1024 -> stringResource(R.string.screen_mxtr_settings_unit_mib, "%.1f".format(n / 1024.0 / 1024))
    else -> stringResource(R.string.screen_mxtr_settings_unit_gib, "%.2f".format(n / 1024.0 / 1024 / 1024))
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("mxtr", text))
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooserTitle = context.getString(R.string.screen_mxtr_settings_share_chooser_title)
    context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}
