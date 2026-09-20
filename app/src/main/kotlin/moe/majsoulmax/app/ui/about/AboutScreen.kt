package moe.majsoulmax.app.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.majsoulmax.app.R
import moe.majsoulmax.app.core.GameLauncher
import moe.majsoulmax.app.data.TunnelSettingsStore
import moe.majsoulmax.app.ui.InfoRow
import moe.majsoulmax.app.ui.SectionCard

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { TunnelSettingsStore.get(context) }
    val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard(title = stringResource(R.string.app_name)) {
            InfoRow(label = stringResource(R.string.about_version), value = version)
            InfoRow(label = stringResource(R.string.about_package), value = context.packageName)
            Text(stringResource(R.string.about_game_only), modifier = Modifier.padding(16.dp))
        }
        SectionCard(title = stringResource(R.string.about_links)) {
            listOf(
                R.string.about_upstream to "https://github.com/Xerxes-2/MajsoulMax-rs",
                R.string.about_repository to "https://github.com/YeFeng233/MajsoulMax-rs-Android",
                R.string.about_game_site to GameLauncher.DOWNLOAD_URL,
            ).forEach { (label, url) ->
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }) { Text(stringResource(label)) }
            }
        }
        SectionCard(title = stringResource(R.string.disclaimer_title)) {
            Text(stringResource(R.string.disclaimer_body), modifier = Modifier.padding(16.dp))
            Text(stringResource(R.string.about_license), modifier = Modifier.padding(16.dp))
            // The guide is skippable, so give the user a way back to it — and to
            // the permission and certificate steps it walks through. Clearing
            // acceptedDisclaimer as well is what re-arms the guide: with it left
            // set, the read-time migration would treat this install as onboarded.
            TextButton(onClick = {
                scope.launch {
                    store.update { it.copy(onboarded = false, acceptedDisclaimer = false) }
                }
            }) {
                Text(stringResource(R.string.about_replay_oobe))
            }
        }
    }
}
