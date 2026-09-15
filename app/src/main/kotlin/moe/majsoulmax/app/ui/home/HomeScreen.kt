package moe.majsoulmax.app.ui.home

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.majsoulmax.app.R
import moe.majsoulmax.app.data.TunnelStatus
import moe.majsoulmax.app.service.TunnelController
import moe.majsoulmax.app.ui.InfoRow
import moe.majsoulmax.app.ui.SectionCard
import moe.majsoulmax.app.ui.web.GameActivity
import java.util.concurrent.TimeUnit

@Composable
fun HomeScreen(
    onOpenCert: () -> Unit,
    onOpenConfig: () -> Unit,
    onOpenLogs: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val context = LocalContext.current
    val status by viewModel.status.collectAsStateWithLifecycle()
    val checks by viewModel.checks.collectAsStateWithLifecycle()
    val now by viewModel.ticker.collectAsStateWithLifecycle()

    // Consent is required once per app install (and again after the user revokes
    // it), so the switch has to route through the system dialog.
    val vpnConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            TunnelController.start(context)
        } else {
            Toast.makeText(context, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshChecks() }

    fun toggle(on: Boolean) {
        if (on) {
            val consent = TunnelController.prepare(context)
            if (consent != null) vpnConsent.launch(consent) else TunnelController.start(context)
        } else {
            TunnelController.stop(context)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PowerCard(status = status, enabled = !checks.blocking, onToggle = ::toggle)

        if (status.stage == TunnelStatus.Stage.ERROR && status.message.isNotBlank()) {
            ErrorCard(message = status.message, onOpenLogs = onOpenLogs)
        }

        SectionCard(
            title = stringResource(R.string.home_checklist),
            trailing = {
                IconButton(onClick = viewModel::refreshChecks) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.check_recheck))
                }
            },
        ) {
            if (checks.loading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            CheckRow(
                ok = checks.assetsReady,
                title = stringResource(R.string.check_assets),
                failureText = stringResource(R.string.check_assets_desc),
            )
            CheckRow(
                ok = checks.certTrusted,
                title = stringResource(R.string.check_cert),
                failureText = stringResource(R.string.check_cert_desc),
                actionText = stringResource(R.string.check_action_fix),
                onAction = onOpenCert,
            )
            CheckRow(
                ok = checks.coreBundled,
                title = stringResource(R.string.check_core),
                failureText = stringResource(R.string.check_core_desc),
            )
            CheckRow(
                ok = checks.kernelBundled,
                title = stringResource(R.string.check_kernel),
                failureText = stringResource(R.string.check_kernel_desc),
            )
            CheckRow(
                ok = checks.tunnelBundled,
                title = stringResource(R.string.check_tunnel),
                failureText = stringResource(R.string.check_tunnel_desc),
            )
            CheckRow(
                ok = checks.notificationsAllowed,
                title = stringResource(R.string.check_notify),
                failureText = stringResource(R.string.check_notify_desc),
                actionText = stringResource(R.string.check_action_fix),
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
        }

        SectionCard(title = stringResource(R.string.home_quick_actions)) {
            ActionRow(
                icon = Icons.Default.OpenInBrowser,
                title = stringResource(R.string.action_open_game),
                subtitle = stringResource(R.string.action_open_game_desc),
                onClick = { context.startActivity(Intent(context, GameActivity::class.java)) },
            )
            ActionRow(
                icon = Icons.Default.VerifiedUser,
                title = stringResource(R.string.action_install_cert),
                subtitle = stringResource(R.string.action_install_cert_desc),
                onClick = onOpenCert,
            )
            ActionRow(
                icon = Icons.Default.Tune,
                title = stringResource(R.string.action_edit_config),
                subtitle = stringResource(R.string.action_edit_config_desc),
                onClick = onOpenConfig,
            )
            ActionRow(
                icon = Icons.Default.Article,
                title = stringResource(R.string.action_view_logs),
                subtitle = stringResource(R.string.action_view_logs_desc),
                onClick = onOpenLogs,
            )
        }

        SectionCard(title = stringResource(R.string.home_runtime)) {
            InfoRow(
                label = stringResource(R.string.info_mitm),
                value = status.mitmAddress.ifBlank { "—" },
                icon = Icons.Default.Router,
            )
            InfoRow(
                label = stringResource(R.string.info_mixed),
                value = if (status.mixedPort > 0) status.mixedPort.toString() else "—",
                icon = Icons.Default.Numbers,
            )
            InfoRow(
                label = stringResource(R.string.info_mode),
                value = stringResource(
                    if (status.proxyOnly) R.string.mode_browser else R.string.mode_vpn,
                ),
            )
            InfoRow(
                label = stringResource(R.string.info_uptime),
                value = formatUptime(status, now),
                icon = Icons.Default.Schedule,
            )
            InfoRow(
                label = stringResource(R.string.info_core_version),
                value = viewModel.coreVersion,
            )
        }

        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                text = stringResource(R.string.about_license),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(UPSTREAM_URL)),
                        )
                    }
                },
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    stringResource(R.string.about_upstream),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PowerCard(status: TunnelStatus, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val running = status.stage == TunnelStatus.Stage.RUNNING
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (running) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            when (status.stage) {
                                TunnelStatus.Stage.STOPPED -> R.string.status_stopped
                                TunnelStatus.Stage.STARTING -> R.string.status_starting
                                TunnelStatus.Stage.RUNNING -> R.string.status_running
                                TunnelStatus.Stage.STOPPING -> R.string.status_stopping
                                TunnelStatus.Stage.ERROR -> R.string.status_error
                            },
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (running) {
                            stringResource(R.string.home_hint_running)
                        } else {
                            status.message.ifBlank { stringResource(R.string.home_hint_stopped) }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (status.stage.isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                } else {
                    Switch(
                        checked = status.stage.isOn,
                        onCheckedChange = onToggle,
                        enabled = enabled,
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onOpenLogs: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenLogs) {
                Text(stringResource(R.string.action_view_logs))
            }
        }
    }
}

@Composable
private fun CheckRow(
    ok: Boolean,
    title: String,
    failureText: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = if (ok) OkGreen else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (!ok) {
                Text(
                    failureText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (!ok && actionText != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionText) }
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val OkGreen = Color(0xFF2E7D32)

private const val UPSTREAM_URL = "https://github.com/Xerxes-2/MajsoulMax-rs"

private fun formatUptime(status: TunnelStatus, now: Long): String {
    if (status.stage != TunnelStatus.Stage.RUNNING || status.startedAt <= 0) return "—"
    val millis = (now - status.startedAt).coerceAtLeast(0)
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
