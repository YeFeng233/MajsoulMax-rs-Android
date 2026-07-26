package moe.majsoulmax.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import moe.majsoulmax.app.R

/**
 * Shared building blocks. Every screen composes from these so spacing, density
 * and affordances stay identical across the app.
 */

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke()
            }
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    SettingRow(title = title, subtitle = subtitle) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
fun TextFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    subtitle: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    enabled: Boolean = true,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = singleLine,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
    }
}

/** Numeric field that only propagates a value once it actually parses. */
@Composable
fun NumberFieldRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    subtitle: String? = null,
    range: IntRange = 0..Int.MAX_VALUE,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    var error by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { next ->
                text = next.filter { it.isDigit() }
                val parsed = text.toIntOrNull()
                if (parsed != null && parsed in range) {
                    error = false
                    onValueChange(parsed)
                } else {
                    error = true
                }
            },
            label = { Text(label) },
            isError = error,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        val hint = if (error) "${range.first} – ${range.last}" else subtitle
        if (!hint.isNullOrBlank()) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = if (error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, icon: ImageVector? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * Editor for a JSON array of scalars, e.g. `sendMethod` or `loadingBg`.
 *
 * Rows are buffered locally rather than read straight back from [values]. That is
 * load-bearing: several callers sanitise what they receive (dropping empty
 * entries, parsing to Int, lowercasing), so a freshly added blank row would be
 * erased before it could ever be typed into. Only committed rows go upward, and
 * an external change to [values] — a reset to defaults, say — is reconciled from
 * an effect rather than during composition.
 */
@Composable
fun ListEditor(
    title: String,
    values: List<String>,
    onChange: (List<String>) -> Unit,
    subtitle: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var rows by remember { mutableStateOf(values) }
    var published by remember { mutableStateOf(values) }

    LaunchedEffect(values) {
        if (values != published) {
            rows = values
            published = values
        }
    }

    fun commit(next: List<String>) {
        rows = next
        published = next
        onChange(next)
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))

        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.list_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        rows.forEachIndexed { index, entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = entry,
                    onValueChange = { next ->
                        commit(rows.toMutableList().also { it[index] = next })
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    commit(rows.toMutableList().also { it.removeAt(index) })
                }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.list_remove))
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        OutlinedButton(onClick = { commit(rows + "") }) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.list_add))
        }
    }
}

/** Editor for `charSkin`-style integer→integer maps. */
@Composable
fun IntMapEditor(
    title: String,
    values: Map<Int, Int>,
    onChange: (Map<Int, Int>) -> Unit,
    subtitle: String? = null,
) {
    // Kept as strings so a half-typed key does not collapse two rows together,
    // and remembered *without* keying on `values` — which this editor rewrites, so
    // keying on it would immediately discard the row being typed into.
    fun snapshot(source: Map<Int, Int>) =
        source.entries.sortedBy { it.key }.map { it.key.toString() to it.value.toString() }

    var rows by remember { mutableStateOf(snapshot(values)) }
    var published by remember { mutableStateOf(values) }

    LaunchedEffect(values) {
        if (values != published) {
            rows = snapshot(values)
            published = values
        }
    }

    fun commit(next: List<Pair<String, String>>) {
        rows = next
        val parsed = next.mapNotNull { (k, v) ->
            val key = k.trim().toIntOrNull() ?: return@mapNotNull null
            val value = v.trim().toIntOrNull() ?: return@mapNotNull null
            key to value
        }.toMap()
        published = parsed
        onChange(parsed)
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))

        rows.forEachIndexed { index, (key, value) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { next ->
                        commit(rows.toMutableList().also { it[index] = next.filter(Char::isDigit) to value })
                    },
                    label = { Text(stringResource(R.string.map_key)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { next ->
                        commit(rows.toMutableList().also { it[index] = key to next.filter(Char::isDigit) })
                    },
                    label = { Text(stringResource(R.string.map_value)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    commit(rows.toMutableList().also { it.removeAt(index) })
                }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.list_remove))
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        OutlinedButton(onClick = { commit(rows + ("" to "")) }) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.list_add))
        }
    }
}

@Composable
fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
