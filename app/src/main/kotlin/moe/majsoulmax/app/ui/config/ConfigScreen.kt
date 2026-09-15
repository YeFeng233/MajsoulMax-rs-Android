package moe.majsoulmax.app.ui.config

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import moe.majsoulmax.app.R
import moe.majsoulmax.app.data.ConfigRepository
import moe.majsoulmax.app.data.TunnelSettings
import moe.majsoulmax.app.data.TunnelStatus
import moe.majsoulmax.app.data.bool
import moe.majsoulmax.app.data.int
import moe.majsoulmax.app.data.intList
import moe.majsoulmax.app.data.intMap
import moe.majsoulmax.app.data.jsonOf
import moe.majsoulmax.app.data.jsonOfIntMap
import moe.majsoulmax.app.data.jsonOfInts
import moe.majsoulmax.app.data.jsonOfNullableString
import moe.majsoulmax.app.data.jsonOfStrings
import moe.majsoulmax.app.data.nullableString
import moe.majsoulmax.app.data.string
import moe.majsoulmax.app.data.stringList
import moe.majsoulmax.app.ui.InfoRow
import moe.majsoulmax.app.ui.IntMapEditor
import moe.majsoulmax.app.ui.ListEditor
import moe.majsoulmax.app.ui.NumberFieldRow
import moe.majsoulmax.app.ui.RowDivider
import moe.majsoulmax.app.ui.SectionCard
import moe.majsoulmax.app.ui.SwitchRow
import moe.majsoulmax.app.ui.TextFieldRow

private enum class ConfigTab(val labelRes: Int) {
    GENERAL(R.string.config_tab_general),
    MOD(R.string.config_tab_mod),
    PROXY(R.string.config_tab_proxy),
    RAW(R.string.config_tab_raw),
}

/**
 * Feature 3: an interactive editor for the configuration files.
 *
 * Typed forms cover everything worth a control; the raw tab covers everything
 * else, validating before it will let you save. Both write through the same
 * patching repository, so neither can lose a field it does not understand.
 */
@Composable
fun ConfigScreen(viewModel: ConfigViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tunnel by viewModel.tunnelSettings.collectAsStateWithLifecycle()
    val message by viewModel.messages.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableIntStateOf(0) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            ConfigTab.entries.forEachIndexed { index, entry ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(stringResource(entry.labelRes)) },
                )
            }
        }

        if (state.loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // Nothing here takes effect until the core reloads, so say so where the
        // user is looking instead of leaving them to wonder.
        if (status.stage == TunnelStatus.Stage.RUNNING) {
            RestartBanner(
                dirty = state.isDirty(ConfigRepository.Which.GENERAL) ||
                    state.isDirty(ConfigRepository.Which.MOD),
                onRestart = viewModel::restartTunnel,
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when (ConfigTab.entries[tab]) {
                ConfigTab.GENERAL -> GeneralTab(viewModel, state)
                ConfigTab.MOD -> ModTab(viewModel, state)
                ConfigTab.PROXY -> ProxyTab(viewModel, tunnel)
                ConfigTab.RAW -> RawTab(viewModel)
            }
        }

        SnackbarHost(hostState = snackbar)
    }
}

@Composable
private fun RestartBanner(dirty: Boolean, onRestart: () -> Unit) {
    Surface(
        color = if (dirty) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (dirty) {
                    stringResource(R.string.config_unsaved)
                } else {
                    stringResource(R.string.config_restart_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRestart) {
                Text(stringResource(R.string.config_restart_now))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// settings.json
// ---------------------------------------------------------------------------

@Composable
private fun GeneralTab(viewModel: ConfigViewModel, state: ConfigViewModel.State) {
    val which = ConfigRepository.Which.GENERAL
    val config = state.effectiveGeneral

    EditorScaffold(
        dirty = state.isDirty(which),
        onSave = { saved, error -> viewModel.save(which, saved, error) },
        onDiscard = { viewModel.discard(which) },
        onReset = { saved -> viewModel.resetToDefaults(which, saved) },
    ) {
        SectionCard(title = stringResource(R.string.config_tab_general)) {
            SwitchRow(
                title = stringResource(R.string.cfg_mod_switch),
                subtitle = stringResource(R.string.cfg_mod_switch_desc),
                checked = config.bool("modSwitch", true),
                onCheckedChange = { viewModel.edit(which, "modSwitch", jsonOf(it)) },
            )
            SwitchRow(
                title = stringResource(R.string.cfg_helper_switch),
                subtitle = stringResource(R.string.cfg_helper_switch_desc),
                checked = config.bool("helperSwitch", false),
                onCheckedChange = { viewModel.edit(which, "helperSwitch", jsonOf(it)) },
            )
            SwitchRow(
                title = stringResource(R.string.cfg_auto_update),
                subtitle = stringResource(R.string.cfg_auto_update_desc),
                checked = config.bool("autoUpdate", true),
                onCheckedChange = { viewModel.edit(which, "autoUpdate", jsonOf(it)) },
            )
            RowDivider()
            TextFieldRow(
                label = stringResource(R.string.cfg_proxy_addr),
                value = config.string("proxyAddr", ConfigRepository.DEFAULT_MITM_ADDRESS),
                onValueChange = { viewModel.edit(which, "proxyAddr", jsonOf(it.trim())) },
            )
            TextFieldRow(
                label = stringResource(R.string.cfg_api_url),
                value = config.string("apiUrl"),
                onValueChange = { viewModel.edit(which, "apiUrl", jsonOf(it.trim())) },
                enabled = config.bool("helperSwitch", false),
            )
            InfoRow(
                label = stringResource(R.string.cfg_liqi_version),
                value = config.string("liqiVersion", "—"),
            )
        }

        SectionCard(title = stringResource(R.string.cfg_github_token)) {
            TextFieldRow(
                label = stringResource(R.string.cfg_github_token),
                value = config.string("githubToken"),
                onValueChange = { viewModel.edit(which, "githubToken", jsonOf(it.trim())) },
                subtitle = stringResource(R.string.cfg_github_token_desc),
            )
            TextFieldRow(
                label = stringResource(R.string.cfg_req_proxy),
                value = config.nullableString("reqProxy"),
                onValueChange = { viewModel.edit(which, "reqProxy", jsonOfNullableString(it)) },
                subtitle = stringResource(R.string.cfg_req_proxy_desc),
            )
        }

        SectionCard(title = stringResource(R.string.cfg_send_method)) {
            ListEditor(
                title = stringResource(R.string.cfg_send_method),
                values = config.stringList("sendMethod"),
                onChange = { viewModel.edit(which, "sendMethod", jsonOfStrings(it)) },
            )
            RowDivider()
            ListEditor(
                title = stringResource(R.string.cfg_send_action),
                values = config.stringList("sendAction"),
                onChange = { viewModel.edit(which, "sendAction", jsonOfStrings(it)) },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// settings.mod.json
// ---------------------------------------------------------------------------

@Composable
private fun ModTab(viewModel: ConfigViewModel, state: ConfigViewModel.State) {
    val which = ConfigRepository.Which.MOD
    val config = state.effectiveMod

    EditorScaffold(
        dirty = state.isDirty(which),
        onSave = { saved, error -> viewModel.save(which, saved, error) },
        onDiscard = { viewModel.discard(which) },
        onReset = { saved -> viewModel.resetToDefaults(which, saved) },
    ) {
        SectionCard(title = stringResource(R.string.config_tab_mod)) {
            SwitchRow(
                title = stringResource(R.string.cfg_hint_switch),
                checked = config.bool("hintSwitch", true),
                onCheckedChange = { viewModel.edit(which, "hintSwitch", jsonOf(it)) },
            )
            SwitchRow(
                title = stringResource(R.string.cfg_emoji_switch),
                checked = config.bool("emojiSwitch", false),
                onCheckedChange = { viewModel.edit(which, "emojiSwitch", jsonOf(it)) },
            )
            SwitchRow(
                title = stringResource(R.string.cfg_show_server),
                checked = config.bool("showServer", true),
                onCheckedChange = { viewModel.edit(which, "showServer", jsonOf(it)) },
            )
            SwitchRow(
                title = stringResource(R.string.cfg_anti_censor),
                checked = config.bool("antiNicknameCensorship", true),
                onCheckedChange = { viewModel.edit(which, "antiNicknameCensorship", jsonOf(it)) },
            )
            SwitchRow(
                title = stringResource(R.string.cfg_mod_auto_update),
                checked = config.bool("autoUpdate", true),
                onCheckedChange = { viewModel.edit(which, "autoUpdate", jsonOf(it)) },
            )
            SwitchRow(
                title = stringResource(R.string.cfg_random_char),
                checked = config.bool("randomCharSwitch", false),
                onCheckedChange = { viewModel.edit(which, "randomCharSwitch", jsonOf(it)) },
            )
        }

        SectionCard(title = stringResource(R.string.cfg_main_char)) {
            NumberFieldRow(
                label = stringResource(R.string.cfg_main_char),
                value = config.int("mainChar", 200001),
                onValueChange = { viewModel.edit(which, "mainChar", jsonOf(it)) },
            )
            TextFieldRow(
                label = stringResource(R.string.cfg_nickname),
                value = config.string("nickname"),
                onValueChange = { viewModel.edit(which, "nickname", jsonOf(it)) },
                subtitle = stringResource(R.string.cfg_nickname_desc),
            )
            NumberFieldRow(
                label = stringResource(R.string.cfg_title),
                value = config.int("title", 0),
                onValueChange = { viewModel.edit(which, "title", jsonOf(it)) },
            )
            NumberFieldRow(
                label = stringResource(R.string.cfg_preset_index),
                value = config.int("presetIndex", 0),
                onValueChange = { viewModel.edit(which, "presetIndex", jsonOf(it)) },
                range = 0..9,
            )
            InfoRow(
                label = stringResource(R.string.cfg_mod_version),
                value = config.string("version", "—"),
            )
        }

        SectionCard(title = stringResource(R.string.cfg_char_skin)) {
            IntMapEditor(
                title = stringResource(R.string.cfg_char_skin),
                subtitle = stringResource(R.string.cfg_char_skin_desc),
                values = config.intMap("charSkin"),
                onChange = { viewModel.edit(which, "charSkin", jsonOfIntMap(it)) },
            )
        }

        SectionCard(title = stringResource(R.string.cfg_star_character)) {
            IntListEditor(
                title = stringResource(R.string.cfg_star_character),
                values = config.intList("starCharacter"),
                onChange = { viewModel.edit(which, "starCharacter", jsonOfInts(it)) },
            )
            RowDivider()
            IntListEditor(
                title = stringResource(R.string.cfg_hidden_characters),
                values = config.intList("hiddenCharacters"),
                onChange = { viewModel.edit(which, "hiddenCharacters", jsonOfInts(it)) },
            )
            RowDivider()
            IntListEditor(
                title = stringResource(R.string.cfg_loading_bg),
                values = config.intList("loadingBg"),
                onChange = { viewModel.edit(which, "loadingBg", jsonOfInts(it)) },
            )
        }
    }
}

/**
 * Integer list on top of the string [ListEditor].
 *
 * A blank or unparsable row publishes 0 rather than vanishing, so adding a row
 * and typing into it behaves the way it looks like it should. [ListEditor] keeps
 * the row visible either way; this only decides what lands in the config.
 */
@Composable
private fun IntListEditor(title: String, values: List<Int>, onChange: (List<Int>) -> Unit) {
    ListEditor(
        title = title,
        values = values.map { it.toString() },
        keyboardType = KeyboardType.Number,
        onChange = { text -> onChange(text.map { it.trim().toIntOrNull() ?: 0 }) },
    )
}

// ---------------------------------------------------------------------------
// Tunnel / kernel settings
// ---------------------------------------------------------------------------

@Composable
private fun ProxyTab(viewModel: ConfigViewModel, settings: TunnelSettings) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(R.string.config_restart_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionCard(title = stringResource(R.string.config_tab_proxy)) {
            NumberFieldRow(
                label = stringResource(R.string.cfg_mixed_port),
                value = settings.mixedPort,
                onValueChange = { port -> viewModel.updateTunnel { it.copy(mixedPort = port) } },
                range = 1024..65535,
            )
            SwitchRow(
                title = stringResource(R.string.cfg_sniff),
                subtitle = stringResource(R.string.cfg_sniff_desc),
                checked = settings.sniff,
                onCheckedChange = { on -> viewModel.updateTunnel { it.copy(sniff = on) } },
            )
            SwitchRow(
                title = stringResource(R.string.cfg_bypass_lan),
                checked = settings.bypassLan,
                onCheckedChange = { on -> viewModel.updateTunnel { it.copy(bypassLan = on) } },
            )
            SwitchRow(
                title = stringResource(R.string.cfg_ipv6),
                checked = settings.ipv6,
                onCheckedChange = { on -> viewModel.updateTunnel { it.copy(ipv6 = on) } },
            )
            RowDivider()
            LogLevelPicker(
                selected = settings.kernelLogLevel,
                onSelect = { level -> viewModel.updateTunnel { it.copy(kernelLogLevel = level) } },
            )
        }

        SectionCard(title = stringResource(R.string.cfg_domains)) {
            ListEditor(
                title = stringResource(R.string.cfg_domains),
                subtitle = stringResource(R.string.cfg_domains_desc),
                values = settings.domainKeywords,
                onChange = { list -> viewModel.updateTunnel { it.copy(domainKeywords = list) } },
            )
        }

        SectionCard(title = stringResource(R.string.cfg_upstream_dns)) {
            ListEditor(
                title = stringResource(R.string.cfg_upstream_dns),
                values = settings.upstreamDns,
                onChange = { list -> viewModel.updateTunnel { it.copy(upstreamDns = list) } },
            )
        }

        SectionCard(title = stringResource(R.string.cfg_extra_rules)) {
            ListEditor(
                title = stringResource(R.string.cfg_extra_rules),
                subtitle = stringResource(R.string.cfg_extra_rules_desc),
                values = settings.extraRules,
                onChange = { list -> viewModel.updateTunnel { it.copy(extraRules = list) } },
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LogLevelPicker(selected: String, onSelect: (String) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(stringResource(R.string.cfg_mihomo_log), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TunnelSettings.LOG_LEVELS.forEach { level ->
                FilterChip(
                    selected = selected == level,
                    onClick = { onSelect(level) },
                    label = { Text(level, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Raw JSON
// ---------------------------------------------------------------------------

@Composable
private fun RawTab(viewModel: ConfigViewModel) {
    var which by remember { mutableStateOf(ConfigRepository.Which.GENERAL) }
    var text by remember { mutableStateOf("") }
    var loadedFor by remember { mutableStateOf<ConfigRepository.Which?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(which) {
        text = viewModel.loadRaw(which)
        loadedFor = which
    }

    val problem = remember(text) { if (text.isBlank()) null else viewModel.validate(text) }
    val savedMessage = stringResource(R.string.config_saved)
    val errorFormat = stringResource(R.string.config_invalid_json)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConfigRepository.Which.entries.forEach { entry ->
                FilterChip(
                    selected = which == entry,
                    onClick = { which = entry },
                    label = { Text(entry.fileName, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        if (problem != null) {
            AssistChip(
                onClick = {},
                label = { Text(errorFormat.format(problem)) },
                leadingIcon = {
                    Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                },
            )
        }

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            isError = problem != null,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.saveRaw(which, text, savedMessage, errorFormat) },
                enabled = problem == null && loadedFor == which,
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.config_save))
            }
            OutlinedButton(
                onClick = { text = viewModel.format(text) },
                enabled = problem == null,
            ) {
                Text(stringResource(R.string.config_format_json))
            }
            OutlinedButton(onClick = {
                scope.launch { text = viewModel.loadRaw(which) }
            }) {
                Text(stringResource(R.string.config_discard))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared editor chrome
// ---------------------------------------------------------------------------

@Composable
private fun EditorScaffold(
    dirty: Boolean,
    onSave: (savedMessage: String, errorFormat: String) -> Unit,
    onDiscard: () -> Unit,
    onReset: (savedMessage: String) -> Unit,
    content: @Composable () -> Unit,
) {
    val savedMessage = stringResource(R.string.config_saved)
    val errorFormat = stringResource(R.string.error_generic)
    var confirmReset by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            content()
            Text(
                stringResource(R.string.config_restart_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { onSave(savedMessage, errorFormat) },
                enabled = dirty,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (dirty) {
                        stringResource(R.string.config_save)
                    } else {
                        stringResource(R.string.config_saved)
                    },
                )
            }
            if (dirty) {
                OutlinedButton(onClick = onDiscard) {
                    Text(stringResource(R.string.config_discard))
                }
            }
            OutlinedButton(onClick = { confirmReset = true }) {
                Icon(
                    Icons.Default.RestartAlt,
                    contentDescription = stringResource(R.string.config_reset),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.config_reset)) },
            text = { Text(stringResource(R.string.config_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onReset(savedMessage)
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
