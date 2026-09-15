package moe.majsoulmax.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import moe.majsoulmax.app.R
import moe.majsoulmax.app.data.TunnelSettingsStore
import moe.majsoulmax.app.ui.apps.AppsScreen
import moe.majsoulmax.app.ui.cert.CertScreen
import moe.majsoulmax.app.ui.config.ConfigScreen
import moe.majsoulmax.app.ui.home.HomeScreen
import moe.majsoulmax.app.ui.logs.LogsScreen

enum class Destination(val route: String, val labelRes: Int, val icon: ImageVector) {
    HOME("home", R.string.nav_home, Icons.Default.Home),
    CERT("cert", R.string.nav_cert, Icons.Default.VerifiedUser),
    CONFIG("config", R.string.nav_config, Icons.Default.Tune),
    LOGS("logs", R.string.nav_logs, Icons.Default.Description),
    APPS("apps", R.string.nav_apps, Icons.Default.Apps),
}

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    DisclaimerGate()

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    val selected = currentRoute?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.HOME.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            composable(Destination.HOME.route) {
                HomeScreen(
                    onOpenCert = { navController.navigate(Destination.CERT.route) },
                    onOpenConfig = { navController.navigate(Destination.CONFIG.route) },
                    onOpenLogs = { navController.navigate(Destination.LOGS.route) },
                )
            }
            composable(Destination.CERT.route) { CertScreen() }
            composable(Destination.CONFIG.route) { ConfigScreen() }
            composable(Destination.LOGS.route) { LogsScreen() }
            composable(Destination.APPS.route) { AppsScreen() }
        }
    }
}

/**
 * Upstream asks that its disclaimer be shown, and a tool that can get an account
 * banned should say so before it is switched on — so this gates the UI once,
 * rather than hiding in an About page.
 */
@Composable
private fun DisclaimerGate() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { TunnelSettingsStore.get(context) }
    val settings by store.settings.collectAsStateWithLifecycle()

    if (settings.acceptedDisclaimer) return

    AlertDialog(
        onDismissRequest = { /* deliberately not dismissible */ },
        title = { Text(stringResource(R.string.disclaimer_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.disclaimer_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch { store.update { it.copy(acceptedDisclaimer = true) } }
            }) {
                Text(stringResource(R.string.disclaimer_accept))
            }
        },
    )
}
