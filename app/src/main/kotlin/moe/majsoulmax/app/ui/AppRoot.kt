package moe.majsoulmax.app.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import moe.majsoulmax.app.ui.about.AboutScreen
import moe.majsoulmax.app.ui.cert.CertScreen
import moe.majsoulmax.app.ui.config.ConfigScreen
import moe.majsoulmax.app.ui.home.HomeScreen
import moe.majsoulmax.app.ui.logs.LogsScreen
import moe.majsoulmax.app.ui.oobe.OobeScreen

enum class Destination(val route: String, val labelRes: Int, val icon: ImageVector) {
    HOME("home", R.string.nav_home, Icons.Default.Home),
    CONFIG("config", R.string.nav_config, Icons.Default.Tune),
    LOGS("logs", R.string.nav_logs, Icons.Default.Description),
    CERT("cert", R.string.nav_cert, Icons.Default.VerifiedUser),
    ABOUT("about", R.string.nav_about, Icons.Default.Info),
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { TunnelSettingsStore.get(context) }
    val settings by store.settings.collectAsStateWithLifecycle()

    // The first-run guide owns the whole window until it is finished, so the
    // bottom navigation cannot be reached from a step that is still unconfirmed.
    if (!settings.onboarded) {
        OobeScreen(
            onFinish = {
                scope.launch {
                    store.update { it.copy(onboarded = true, acceptedDisclaimer = true) }
                }
            },
        )
        return
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

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
            // Slides rather than the default fade. The offsets come from
            // compose.animation directly, so no navigation-package internals are
            // referenced and the code cannot drift with the navigation version.
            //
            // Both directions travel a full width. A shallower exit (the old
            // `-it / 3`) left the outgoing page parked on screen for the whole
            // animation, so its cards stayed visible on top of the incoming
            // page — measured on device as the previous page's rows showing
            // through wherever the new page had no content of its own. With
            // full travel the two pages tile edge to edge and never overlap.
            enterTransition = { slideInHorizontally(tween(250)) { it } },
            exitTransition = { slideOutHorizontally(tween(250)) { -it } },
            popEnterTransition = { slideInHorizontally(tween(250)) { -it } },
            popExitTransition = { slideOutHorizontally(tween(250)) { it } },
        ) {
            composable(Destination.HOME.route) {
                ScreenSurface {
                    HomeScreen(
                        onOpenCert = { navController.navigate(Destination.CERT.route) },
                        onOpenLogs = { navController.navigate(Destination.LOGS.route) },
                    )
                }
            }
            composable(Destination.CERT.route) { ScreenSurface { CertScreen() } }
            composable(Destination.CONFIG.route) { ScreenSurface { ConfigScreen() } }
            composable(Destination.LOGS.route) { ScreenSurface { LogsScreen() } }
            composable(Destination.ABOUT.route) { ScreenSurface { AboutScreen() } }
        }
    }
}

/**
 * Paints an opaque background behind one destination.
 *
 * The slide transition keeps both destinations composed for the length of the
 * animation. Compose destinations are transparent by default — the Scaffold
 * paints *behind* the NavHost — so without this the outgoing page's cards show
 * through the incoming one wherever the incoming page has no content.
 */
@Composable
private fun ScreenSurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) { content() }
}
