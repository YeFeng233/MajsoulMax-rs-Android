package moe.majsoulmax.app.ui.logs

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.majsoulmax.app.R
import moe.majsoulmax.app.core.LogStore
import moe.majsoulmax.app.core.Paths
import moe.majsoulmax.app.ui.theme.MonoStyle

class LogsViewModel(application: Application) : AndroidViewModel(application) {

    /** Bounded so a long debug session cannot grow the UI's heap without limit. */
    private val maxLines = 2_000

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    init {
        viewModelScope.launch {
            LogStore.tail(getApplication()).collect { chunk ->
                if (chunk.isEmpty()) {
                    _lines.value = emptyList()
                } else {
                    val incoming = chunk.split('\n').filter { it.isNotBlank() }
                    _lines.value = (_lines.value + incoming).takeLast(maxLines)
                }
            }
        }
    }

    fun clear() {
        viewModelScope.launch {
            LogStore.clear(getApplication())
            _lines.value = emptyList()
        }
    }
}

@Composable
fun LogsScreen(viewModel: LogsViewModel = viewModel()) {
    val context = LocalContext.current
    val lines by viewModel.lines.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var autoScroll by remember { mutableStateOf(true) }
    var wrap by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<String?>(null) }

    val visible = remember(lines, filter) {
        filter?.let { needle -> lines.filter { it.contains(needle, ignoreCase = true) } } ?: lines
    }

    LaunchedEffect(visible.size, autoScroll) {
        if (autoScroll && visible.isNotEmpty()) {
            listState.animateScrollToItem(visible.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = filter == null,
                    onClick = { filter = null },
                    label = { Text(stringResource(R.string.logs_filter_all)) },
                )
                // Sources match the prefixes written by the Rust core, the kernel
                // pump and the service.
                listOf("svc:" to "svc", "meta:" to "meta", "WARN" to "warn", "ERROR" to "error")
                    .forEach { (needle, label) ->
                        FilterChip(
                            selected = filter == needle,
                            onClick = { filter = if (filter == needle) null else needle },
                            label = { Text(label) },
                        )
                    }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { autoScroll = !autoScroll }) {
                Icon(
                    Icons.Default.VerticalAlignBottom,
                    contentDescription = stringResource(R.string.logs_autoscroll),
                    tint = if (autoScroll) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = { wrap = !wrap }) {
                Icon(
                    Icons.Default.WrapText,
                    contentDescription = stringResource(R.string.logs_wrap),
                    tint = if (wrap) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { copyLogs(context, visible) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.logs_copy))
            }
            IconButton(onClick = { shareLogs(context) }) {
                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.logs_share))
            }
            IconButton(onClick = viewModel::clear) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.logs_clear))
            }
        }

        if (visible.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.logs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
        ) {
            items(visible) { line ->
                Text(
                    text = line,
                    style = MonoStyle,
                    color = colorFor(line),
                    maxLines = if (wrap) Int.MAX_VALUE else 1,
                    overflow = if (wrap) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun colorFor(line: String) = when {
    line.contains("ERROR") || line.startsWith("!!!") -> MaterialTheme.colorScheme.error
    line.contains("WARN") -> MaterialTheme.colorScheme.tertiary
    line.startsWith("===") || line.startsWith("-->") -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurface
}

private fun copyLogs(context: Context, lines: List<String>) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("majsoulmax-log", lines.joinToString("\n")))
}

private fun shareLogs(context: Context) {
    val file = Paths.logFile(context)
    if (!file.exists()) return
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }.getOrNull() ?: return

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}
