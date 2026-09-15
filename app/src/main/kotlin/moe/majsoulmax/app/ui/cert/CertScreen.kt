package moe.majsoulmax.app.ui.cert

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.majsoulmax.app.R
import moe.majsoulmax.app.core.AssetInstaller
import moe.majsoulmax.app.core.CertManager
import moe.majsoulmax.app.ui.InfoRow
import moe.majsoulmax.app.ui.SectionCard

class CertViewModel(application: Application) : AndroidViewModel(application) {

    private val _info = MutableStateFlow<CertManager.CertInfo?>(null)
    val info: StateFlow<CertManager.CertInfo?> = _info.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _busy.value = true
            val context = getApplication<Application>()
            AssetInstaller.ensure(context)
            _info.value = CertManager.load(context)
            _busy.value = false
        }
    }

    /** @return the user-visible export location, or null on failure. */
    suspend fun export(): String? = CertManager.exportForManualInstall(getApplication())
}

/**
 * Feature 1: get the user to the certificate installer in one tap.
 *
 * Android has made this progressively harder — the in-app installer intent stops
 * working somewhere around Android 11 depending on the OEM — so this screen tries
 * the direct route first, falls back through a mime-typed hand-off and the
 * Settings entry point, and always offers the manual path with the exported file
 * plus verbatim steps. It also reports the *actual* trust state read back from
 * the system store, so the user never has to guess whether it worked.
 */
@Composable
fun CertScreen(viewModel: CertViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val info by viewModel.info.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // Re-check trust as soon as we come back from Settings: this is exactly when
    // the answer changes.
    val installFlow = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.refresh() }

    val legacyStoragePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scope.launch { exportAndReport(viewModel, context, snackbar) }
        }
    }

    fun launchInstall() {
        val candidates = CertManager.installIntents(context)
        if (candidates.isEmpty()) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.cert_install_unsupported)) }
            return
        }
        // Candidates are unfiltered by design (see CertManager.installIntents), so
        // the first one that actually starts wins.
        for (intent in candidates) {
            val ok = runCatching { installFlow.launch(intent) }.isSuccess
            if (ok) return
        }
        scope.launch { snackbar.showSnackbar(context.getString(R.string.cert_install_unsupported)) }
    }

    fun exportCert() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            legacyStoragePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            scope.launch { exportAndReport(viewModel, context, snackbar) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StateCard(trusted = info?.trusted == true, expired = info?.expired == true)

            Text(
                text = stringResource(R.string.cert_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = ::launchInstall, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.cert_install))
                }
                OutlinedButton(onClick = viewModel::refresh, enabled = !busy) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.check_recheck))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = ::exportCert, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.cert_export))
                }
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS),
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.cert_open_settings))
                }
            }

            info?.let { detail ->
                SectionCard(title = stringResource(R.string.cert_title)) {
                    InfoRow(stringResource(R.string.cert_detail_subject), detail.subject)
                    InfoRow(stringResource(R.string.cert_detail_issuer), detail.issuer)
                    InfoRow(
                        stringResource(R.string.cert_detail_valid),
                        "${detail.notBefore} – ${detail.notAfter}",
                    )
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            stringResource(R.string.cert_detail_sha256),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            detail.sha256,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        TextButton(onClick = {
                            copyToClipboard(context, detail.sha256)
                            scope.launch {
                                snackbar.showSnackbar(context.getString(R.string.cert_copied))
                            }
                        }) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.cert_copy_fingerprint))
                        }
                    }
                }
            }

            SectionCard(title = stringResource(R.string.cert_manual_title)) {
                Text(
                    stringResource(R.string.cert_manual_steps),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

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

            Spacer(Modifier.height(8.dp))
        }

        SnackbarHost(hostState = snackbar)
    }
}

@Composable
private fun StateCard(trusted: Boolean, expired: Boolean) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (trusted && !expired) {
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

private suspend fun exportAndReport(
    viewModel: CertViewModel,
    context: Context,
    snackbar: SnackbarHostState,
) {
    val location = viewModel.export()
    snackbar.showSnackbar(
        if (location != null) {
            context.getString(R.string.cert_exported, location)
        } else {
            context.getString(R.string.cert_export_failed, "I/O")
        },
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("hudsucker", text))
}
