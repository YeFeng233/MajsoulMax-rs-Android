package moe.majsoulmax.app.service

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

    fun start(context: Context) {
        val intent = Intent(context, MajsoulVpnService::class.java)
            .setAction(MajsoulVpnService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /**
     * Starts only the MITM proxy — no VPN consent, no tun, no kernel. Used by the
     * built-in browser, which reaches the proxy through a WebView proxy override.
     */
    fun startProxyOnly(context: Context) {
        val intent = Intent(context, MajsoulVpnService::class.java)
            .setAction(MajsoulVpnService.ACTION_START)
            .putExtra(MajsoulVpnService.EXTRA_PROXY_ONLY, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop(context: Context) {
        val intent = Intent(context, MajsoulVpnService::class.java)
            .setAction(MajsoulVpnService.ACTION_STOP)
        // The service is already foreground at this point, so a plain start is
        // enough and avoids a needless foreground-start on a stopped service.
        runCatching { context.startService(intent) }
            .onFailure { Log.w(TAG, "stop request failed", it) }
    }
}
