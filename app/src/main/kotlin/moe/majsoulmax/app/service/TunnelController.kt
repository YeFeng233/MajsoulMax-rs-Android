package moe.majsoulmax.app.service

import android.app.ActivityManager
import kotlinx.coroutines.*
import moe.majsoulmax.app.data.TunnelStatus
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log

/**
 * The UI's only handle on the tunnel. Keeps intent plumbing in one place so
 * screens never talk to the service directly.
 */
object TunnelController {

    private const val TAG = "TunnelController"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingStart: Job? = null

    /**
     * @return the consent intent to launch, or null when permission is already
     *         granted and [start] can be called straight away.
     */
    fun prepare(context: Context): Intent? = try {
        VpnService.prepare(context)
    } catch (e: Exception) {
        // Some ROMs throw here when another always-on VPN owns the slot.
        Log.e(TAG, "VpnService.prepare failed", e)
        null
    }

    fun start(context: Context) = requestStart(context, proxyOnly = false)

    fun startProxyOnly(context: Context) = requestStart(context, proxyOnly = true)

    private fun requestStart(context: Context, proxyOnly: Boolean) {
        val app = context.applicationContext
        pendingStart?.cancel()
        pendingStart = scope.launch {
            // STOPPED is published immediately before core exit. Wait for that
            // exit, including a STOPPING run, instead of racing its final cleanup.
            val ready = withTimeoutOrNull(15_000L) {
                while (coreProcessExists(app) && !TunnelStatus.read(app).stage.isOn) delay(100)
                true
            } ?: false
            if (!ready) {
                Log.w(TAG, "old core is still stopping; refusing overlapping start")
                return@launch
            }
            val intent = Intent(app, MajsoulVpnService::class.java)
                .setAction(MajsoulVpnService.ACTION_START)
                .putExtra(MajsoulVpnService.EXTRA_PROXY_ONLY, proxyOnly)
            app.startForegroundService(intent)
        }
    }

    private fun coreProcessExists(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty()
            .any { it.processName == context.packageName + ":core" }

    fun stop(context: Context) {
        pendingStart?.cancel()
        val intent = Intent(context, MajsoulVpnService::class.java)
            .setAction(MajsoulVpnService.ACTION_STOP)
        // The service is already foreground at this point, so a plain start is
        // enough and avoids a needless foreground-start on a stopped service.
        runCatching { context.startService(intent) }
            .onFailure { Log.w(TAG, "stop request failed", it) }
    }
}
