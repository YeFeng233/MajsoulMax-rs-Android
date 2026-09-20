package moe.majsoulmax.app.ui.oobe

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.majsoulmax.app.R
import moe.majsoulmax.app.core.AssetInstaller
import moe.majsoulmax.app.core.CertManager
import moe.majsoulmax.app.service.NotificationHelper
import moe.majsoulmax.app.service.TunnelController
import moe.majsoulmax.app.ui.CheckRow
import moe.majsoulmax.app.ui.SectionCard

/**
 * First-run guide, shown until `TunnelSettings.onboarded` is set.
 *
 * Three steps in the order the tunnel needs them: the VPN and notification
 * permissions, the MITM certificate, and the disclaimer the user has to accept
 * before any traffic is touched. It reuses the home checklist's row, the
 * certificate screen's actions and the core managers, so nothing is duplicated
 * and the guide reads as part of the same app rather than a separate wizard.
 *
 * Steps are never a dead end: only the final agreement is required, and About can
 * bring the guide back.
 */
private enum class OobeStep(val labelRes: Int) {
    PERMISSIONS(R.string.oobe_step_permissions),
    CERTIFICATE(R.string.oobe_step_certificate),
    TERMS(R.string.oobe_step_terms),
}

@Composable
fun OobeScreen(onFinish: () -> Unit) {
    val steps = OobeStep.entries
    var step by remember { mutableIntStateOf(0) }
    var agreed by remember { mutableStateOf(false) }
    val lastIndex = steps.lastIndex

    BackHandler(enabled = step > 0) { step-- }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.oobe_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(18.dp))
            StepIndicator(current = step, count = steps.size, labelRes = steps[step].labelRes)
            Spacer(Modifier.height(18.dp))

            AnimatedContent(
                targetState = step,
                modifier = Modifier.weight(1f),
                // Full-width travel on both sides: the two steps tile instead of
                // overlapping, which is what keeps the outgoing step's cards from
                // showing through the incoming one.
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    slideInHorizontally(tween(250)) { it * direction } togetherWith
                        slideOutHorizontally(tween(250)) { -it * direction }
                },
                label = "OobeStep",
            ) { index ->
                when (steps[index]) {
                    OobeStep.PERMISSIONS -> PermissionsStep()
                    OobeStep.CERTIFICATE -> CertificateStep()
                    OobeStep.TERMS -> TermsStep(
                        agreed = agreed,
                        onAgreedChange = { agreed = it },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) {
                    TextButton(onClick = { step-- }) {
                        Text(stringResource(R.string.oobe_back))
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { if (step == lastIndex) onFinish() else step++ },
                    // The agreement is the one thing that cannot be skipped.
                    enabled = step != lastIndex || agreed,
                ) {
                    Text(
                        stringResource(
                            if (step == lastIndex) R.string.oobe_finish else R.string.oobe_next,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(current: Int, count: Int, labelRes: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(count) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(
                            color = if (index <= current) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            shape = RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.oobe_step_of, current + 1, count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Step 1 — the two runtime permissions the tunnel needs. */
@Composable
private fun PermissionsStep() {
    val context = LocalContext.current
    var vpnGranted by remember { mutableStateOf(vpnPrepared(context)) }
    var notifyGranted by remember { mutableStateOf(NotificationHelper.hasPermission(context)) }

    val vpnConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { vpnGranted = vpnPrepared(context) }

    val notifyRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notifyGranted = NotificationHelper.hasPermission(context) }

    // The consent dialog and the system settings page both live outside this
    // process, so re-read the state whenever the step is (re)composed.
    LaunchedEffect(Unit) {
        vpnGranted = vpnPrepared(context)
        notifyGranted = NotificationHelper.hasPermission(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(title = stringResource(R.string.oobe_step_permissions)) {
            CheckRow(
                ok = vpnGranted,
                title = stringResource(R.string.oobe_perm_vpn),
                failureText = stringResource(R.string.oobe_perm_vpn_desc),
                actionText = stringResource(R.string.oobe_grant),
                onAction = {
                    val consent = TunnelController.prepare(context)
                    if (consent == null) {
                        vpnGranted = vpnPrepared(context)
                    } else {
                        vpnConsent.launch(consent)
                    }
                },
            )
            CheckRow(
                ok = notifyGranted,
                title = stringResource(R.string.oobe_perm_notify),
                failureText = stringResource(R.string.oobe_perm_notify_desc),
                actionText = stringResource(R.string.oobe_grant),
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifyRequest.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        openNotificationSettings(context)
                    }
                },
            )
        }

        Text(
            text = stringResource(R.string.oobe_perm_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.about_game_only),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Step 2 — the MITM certificate, without which HTTPS stays encrypted. */
@Composable
private fun CertificateStep() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var trusted by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var unsupported by remember { mutableStateOf(false) }

    // Re-check as soon as we come back from the installer: that is exactly when
    // the answer changes.
    val installFlow = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { scope.launch { trusted = readTrust(context) } }

    fun refresh() {
        scope.launch {
            busy = true
            trusted = readTrust(context)
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun install() {
        val candidates = CertManager.installIntents(context)
        unsupported = candidates.isEmpty()
        for (intent in candidates) {
            if (runCatching { installFlow.launch(intent) }.isSuccess) {
                unsupported = false
                return
            }
        }
        unsupported = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TrustCard(trusted = trusted)

        Text(
            text = stringResource(R.string.oobe_cert_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = ::install, modifier = Modifier.weight(1f)) {
                Icon(
                    Icons.Default.VerifiedUser,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.cert_install))
            }
            OutlinedButton(onClick = ::refresh, enabled = !busy) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.check_recheck),
                )
            }
        }

        WarningCard()

        Text(
            text = stringResource(
                if (unsupported) R.string.oobe_cert_unsupported else R.string.oobe_cert_manual_hint,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Step 3 — the notice the user has to actually accept. */
@Composable
private fun TermsStep(agreed: Boolean, onAgreedChange: (Boolean) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(title = stringResource(R.string.disclaimer_title)) {
            Text(
                text = stringResource(R.string.disclaimer_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        SectionCard(title = stringResource(R.string.oobe_terms_points_title)) {
            Text(
                text = stringResource(R.string.oobe_terms_points),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = agreed, onCheckedChange = onAgreedChange)
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.oobe_terms_agree),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun TrustCard(trusted: Boolean) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (trusted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (trusted) Icons.Default.GppGood else Icons.Default.GppBad,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(
                    if (trusted) R.string.cert_state_trusted else R.string.cert_state_untrusted,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun WarningCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.WarningAmber,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.cert_warning_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.cert_warning_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** True when the system already holds a VPN consent for this app. */
private fun vpnPrepared(context: Context): Boolean =
    runCatching { VpnService.prepare(context) }.getOrNull() == null

private suspend fun readTrust(context: Context): Boolean {
    // Unpack first: on a fresh install the certificate file does not exist yet,
    // and an untrusted answer for a missing file reads like a failed install.
    AssetInstaller.ensure(context)
    return CertManager.isTrusted(context)
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent) }
}
