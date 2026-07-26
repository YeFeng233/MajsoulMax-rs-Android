package moe.majsoulmax.app.ui.apps

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.majsoulmax.app.R
import moe.majsoulmax.app.data.TunnelSettings
import moe.majsoulmax.app.data.TunnelSettingsStore

class AppsViewModel(application: Application) : AndroidViewModel(application) {

    data class Entry(val packageName: String, val label: String, val system: Boolean)

    private val store = TunnelSettingsStore.get(application)
    val settings: StateFlow<TunnelSettings> = store.settings

    private val _apps = MutableStateFlow<List<Entry>>(emptyList())
    val apps: StateFlow<List<Entry>> = _apps.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        viewModelScope.launch {
            _apps.value = withContext(Dispatchers.IO) { loadApps() }
            _loading.value = false
        }
    }

    /**
     * Only apps with a launcher entry, which is both what the user recognises and
     * what keeps us off the QUERY_ALL_PACKAGES permission.
     */
    private fun loadApps(): List<Entry> {
        val context = getApplication<Application>()
        val pm = context.packageManager
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        ).mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }

        return launchable
            .filter { it.packageName != context.packageName }
            .map { info ->
                Entry(
                    packageName = info.packageName,
                    label = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName),
                    system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    fun setMode(mode: TunnelSettings.RoutingMode) {
        viewModelScope.launch { store.update { it.copy(routingMode = mode) } }
    }

    fun toggle(packageName: String, selected: Boolean) {
        viewModelScope.launch {
            store.update { current ->
                current.copy(
                    selectedApps = if (selected) {
                        current.selectedApps + packageName
                    } else {
                        current.selectedApps - packageName
                    },
                )
            }
        }
    }
}

/**
 * Per-app routing. Useful in practice because a system-wide tunnel makes every
 * other app's traffic take a detour through the kernel for no benefit.
 */
@Composable
fun AppsScreen(viewModel: AppsViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }

    val visible = remember(apps, query, showSystem) {
        apps.filter { entry ->
            (showSystem || !entry.system) &&
                (
                    query.isBlank() ||
                        entry.label.contains(query, ignoreCase = true) ||
                        entry.packageName.contains(query, ignoreCase = true)
                    )
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.apps_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(TunnelSettings.RoutingMode.ALL, R.string.apps_mode_all, settings, viewModel)
                ModeChip(TunnelSettings.RoutingMode.ALLOW, R.string.apps_mode_allow, settings, viewModel)
                ModeChip(TunnelSettings.RoutingMode.DENY, R.string.apps_mode_deny, settings, viewModel)
            }
        }

        if (settings.routingMode != TunnelSettings.RoutingMode.ALL) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.apps_search)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = showSystem, onCheckedChange = { showSystem = it })
                Text(stringResource(R.string.apps_show_system), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.apps_selected, settings.selectedApps.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (loading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(visible, key = { it.packageName }) { entry ->
                        val checked = entry.packageName in settings.selectedApps
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.toggle(entry.packageName, !checked) }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { viewModel.toggle(entry.packageName, it) },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    entry.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(
    mode: TunnelSettings.RoutingMode,
    labelRes: Int,
    settings: TunnelSettings,
    viewModel: AppsViewModel,
) {
    FilterChip(
        selected = settings.routingMode == mode,
        onClick = { viewModel.setMode(mode) },
        label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall) },
    )
}
