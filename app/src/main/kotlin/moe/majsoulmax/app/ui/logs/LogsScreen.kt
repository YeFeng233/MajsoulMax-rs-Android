package moe.majsoulmax.app.ui.logs

import android.app.Application
import android.content.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import moe.majsoulmax.app.R
import moe.majsoulmax.app.core.LogStore
import moe.majsoulmax.app.core.Paths
import moe.majsoulmax.app.ui.theme.MonoStyle
import java.io.File

enum class LogSource(val label: Int) { APP(R.string.logs_source_app), CORE(R.string.logs_source_core) }

class LogsViewModel(application: Application) : AndroidViewModel(application) {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines = _lines.asStateFlow()
    private val _source = MutableStateFlow(LogSource.CORE)
    val source = _source.asStateFlow()
    private var tailJob: Job? = null
    init { follow(LogSource.CORE) }
    fun select(source: LogSource) { if (_source.value != source) { _source.value = source; follow(source) } }
    private fun file(source: LogSource): File = if (source == LogSource.APP) Paths.appLogFile(getApplication()) else Paths.logFile(getApplication())
    private fun follow(source: LogSource) {
        tailJob?.cancel(); _lines.value = emptyList()
        tailJob = viewModelScope.launch { LogStore.tail(file(source)).collect { chunk -> _lines.value = if (chunk.isEmpty()) emptyList() else (_lines.value + chunk.split('\n').filter(String::isNotBlank)).takeLast(2000) } }
    }
    fun clear() { viewModelScope.launch { LogStore.clearFile(file(_source.value)); _lines.value = emptyList() } }
    fun currentFile(): File = file(_source.value)
}

@Composable
fun LogsScreen(viewModel: LogsViewModel = viewModel()) {
    val context = LocalContext.current
    val lines by viewModel.lines.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val state = rememberLazyListState()
    var autoScroll by remember { mutableStateOf(true) }
    var wrap by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<String?>(null) }
    var formatted by remember { mutableStateOf(true) }
    LaunchedEffect(source) { filter = null }
    val visible = remember(lines, filter) { filter?.let { needle -> lines.filter { it.contains(needle, true) } } ?: lines }
    LaunchedEffect(visible.size, autoScroll) { if (autoScroll && visible.isNotEmpty()) state.animateScrollToItem(visible.lastIndex) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LogSource.entries.forEach { item -> FilterChip(source == item, { viewModel.select(item) }, label = { Text(stringResource(item.label)) }) }
            FilterChip(formatted, { formatted = true }, label = { Text(stringResource(R.string.logs_formatted)) })
            FilterChip(!formatted, { formatted = false }, label = { Text(stringResource(R.string.logs_raw)) })
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(filter == null, { filter = null }, label = { Text(stringResource(R.string.logs_filter_all)) })
            val filters = if (source == LogSource.APP) listOf(" I/" to "info", " W/" to "warn", " E/" to "error") else listOf("svc:" to "svc", "meta:" to "meta", "WARN" to "warn", "ERROR" to "error")
            filters.forEach { (needle, label) -> FilterChip(filter == needle, { filter = if (filter == needle) null else needle }, label = { Text(label) }) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ autoScroll = !autoScroll }) { Icon(Icons.Default.VerticalAlignBottom, stringResource(R.string.logs_autoscroll), tint = if (autoScroll) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton({ wrap = !wrap }) { Icon(Icons.AutoMirrored.Filled.WrapText, stringResource(R.string.logs_wrap), tint = if (wrap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.weight(1f))
            IconButton({ copyLogs(context, visible) }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.logs_copy)) }
            IconButton({ shareLogs(context, viewModel.currentFile()) }) { Icon(Icons.Default.Share, stringResource(R.string.logs_share)) }
            IconButton(viewModel::clear) { Icon(Icons.Default.Delete, stringResource(R.string.logs_clear)) }
        }
        if (visible.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.logs_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        else LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) { items(visible) { line -> Text(if (formatted) formatLogLine(line) else line, style = MonoStyle, color = colorFor(line), maxLines = if (wrap) Int.MAX_VALUE else 1, overflow = if (wrap) TextOverflow.Clip else TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth()) } }
    }
}

private fun formatLogLine(line: String): String {
    val marker = line.indexOf(" I/").takeIf { it >= 0 } ?: line.indexOf(" W/").takeIf { it >= 0 } ?: line.indexOf(" E/").takeIf { it >= 0 }
    return if (marker != null) "${line.substring(0, marker)}  ${line.substring(marker + 1)}" else line
}

@Composable private fun colorFor(line: String) = when { line.contains("ERROR") || line.contains(" E/") -> MaterialTheme.colorScheme.error; line.contains("WARN") || line.contains(" W/") -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.onSurface }
private fun copyLogs(context: Context, lines: List<String>) { (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("majsoulmax-log", lines.joinToString("\n"))) }
private fun shareLogs(context: Context, file: File) { if (!file.exists()) return; val uri = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", file) }.getOrNull() ?: return; val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }; runCatching { context.startActivity(Intent.createChooser(intent, null)) } }
