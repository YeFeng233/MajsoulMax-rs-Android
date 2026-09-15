package moe.majsoulmax.app.service

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.majsoulmax.app.MainActivity
import moe.majsoulmax.app.R
import moe.majsoulmax.app.core.AssetInstaller
import moe.majsoulmax.app.core.LogStore
import moe.majsoulmax.app.core.MihomoKernel
import moe.majsoulmax.app.core.MitmNative
import moe.majsoulmax.app.core.Tun2SocksConfig
import moe.majsoulmax.app.core.Tun2SocksNative
import moe.majsoulmax.app.data.ConfigRepository
import moe.majsoulmax.app.data.TunnelSettings
import moe.majsoulmax.app.data.TunnelSettingsStore
import moe.majsoulmax.app.data.TunnelStatus
import moe.majsoulmax.app.data.bool

/**
 * Brings up the whole chain and tears it down again:
 *
 * ```
 *   app traffic → tun → hev-socks5-tunnel → Meta kernel (rules) → MITM proxy → internet
 *                                                    └────────── DIRECT ──────┘
 * ```
 *
 * Runs in the `:core` process (see the manifest) and deliberately kills that
 * process once the tunnel stops, which reclaims the intentionally-leaked
 * `&'static Settings` on the Rust side and guarantees the next start reads fresh
 * config from disk.
 *
 * Loopback protection lives here rather than in the kernel's rules: this app is
 * excluded from the VPN, so the kernel's own outbound connections and the MITM
 * proxy's upstream connections cannot be routed back into the tunnel.
 */
class MajsoulVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var startJob: Job? = null

    /**
     * Must outlive the native tunnel: closing it pulls the descriptor out from
     * under hev-socks5-tunnel.
     */
    private var tunnel: ParcelFileDescriptor? = null

    @Volatile
    private var stage: TunnelStatus.Stage = TunnelStatus.Stage.STOPPED

    private var startedAt = 0L
    private var mitmAddress = ""
    private var mixedPort = 0

    /**
     * Proxy-only runs skip the kernel and the tun entirely: the built-in browser
     * points its WebView straight at the MITM proxy, which needs no VPN consent
     * and no routing rules.
     */
    @Volatile
    private var proxyOnly = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                if (stage.isOn) {
                    Log.i(TAG, "already ${stage.name.lowercase()}, ignoring start")
                    return START_NOT_STICKY
                }
                proxyOnly = intent?.getBooleanExtra(EXTRA_PROXY_ONLY, false) == true
                // Android gives us seconds to get into the foreground, and startup
                // below can take much longer than that, so claim it immediately.
                publish(TunnelStatus.Stage.STARTING, getString(R.string.status_starting))
                startForegroundNotification(getString(R.string.status_starting))
                startJob = scope.launch { startTunnel() }
                return START_NOT_STICKY
            }

            else -> return START_NOT_STICKY
        }
    }

    // -----------------------------------------------------------------------
    // Startup
    // -----------------------------------------------------------------------

    private suspend fun startTunnel() {
        try {
            LogStore.rotateIfNeeded(this)
            log("=== starting tunnel ===")

            val tunnelSettings = TunnelSettingsStore.read(this)

            step("unpacking configuration") {
                val result = AssetInstaller.ensure(this)
                if (!result.ok) error("cannot unpack assets: ${result.error}")
            }

            val general = ConfigRepository(this).load(ConfigRepository.Which.GENERAL)
            val (mitmHost, mitmPort) = ConfigRepository.mitmEndpoint(general)
            mitmAddress = "$mitmHost:$mitmPort"
            mixedPort = tunnelSettings.mixedPort

            step("starting the MITM core") {
                MitmNative.start(this)?.let { error("MITM core: $it") }
                awaitMitmRunning()
            }

            if (proxyOnly) {
                mixedPort = 0
                log("proxy-only mode: skipping the Meta kernel and the tun interface")
            } else {
                step("starting the Meta kernel") {
                    MihomoKernel.start(this, tunnelSettings, mitmHost, mitmPort)
                        ?.let { error("Meta kernel: $it") }
                }

                step("establishing the tun interface") {
                    val descriptor = withContext(Dispatchers.Main) { establish(tunnelSettings) }
                        ?: error("VpnService.establish() returned null — permission revoked?")
                    tunnel = descriptor

                    Tun2SocksNative.start(Tun2SocksConfig.build(tunnelSettings), descriptor.fd)
                        ?.let { error("tunnel: $it") }
                }
            }

            startedAt = System.currentTimeMillis()
            publish(
                TunnelStatus.Stage.RUNNING,
                getString(R.string.status_running),
                modEnabled = general.bool("modSwitch", true),
                helperEnabled = general.bool("helperSwitch", false),
            )
            updateNotification(
                if (proxyOnly) mitmAddress else getString(R.string.notif_running_desc, mitmAddress, mixedPort),
            )
            log("=== tunnel is up: mitm=$mitmAddress meta=$mixedPort ===")
        } catch (e: Throwable) {
            val message = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "startup failed", e)
            log("!!! startup failed: $message")
            teardown()
            publish(TunnelStatus.Stage.ERROR, message)
            // The notification is gone with the foreground state; the UI reads
            // ERROR from status.json and surfaces the reason.
            stopSelfAndProcess(killProcess = true)
        }
    }

    private inline fun step(description: String, block: () -> Unit) {
        log("--> $description")
        publish(TunnelStatus.Stage.STARTING, description)
        updateNotification(description)
        block()
    }

    /**
     * The Rust core starts asynchronously because it may refresh `liqi.json` and
     * `lqc.lqbin` over the network first, so we poll rather than assume.
     */
    private suspend fun awaitMitmRunning() {
        val deadline = System.currentTimeMillis() + MITM_START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            when (MitmNative.state) {
                MitmNative.State.RUNNING -> return
                MitmNative.State.ERROR ->
                    error(MitmNative.lastError ?: "MITM core failed to start")

                MitmNative.State.UNAVAILABLE -> error("libmajsoulmax.so is missing for this ABI")
                MitmNative.State.STOPPED -> error("the MITM core stopped during startup")
                MitmNative.State.STARTING, MitmNative.State.STOPPING -> delay(200)
            }
        }
        error("the MITM core did not come up within ${MITM_START_TIMEOUT_MS / 1000}s")
    }

    private fun establish(settings: TunnelSettings): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(settings.mtu)
            .addAddress(Tun2SocksConfig.TUN_ADDRESS, Tun2SocksConfig.TUN_PREFIX)

        settings.upstreamDns.ifEmpty { TunnelSettings.DEFAULT_DNS }.forEach { dns ->
            runCatching { builder.addDnsServer(dns) }
                .onFailure { log("ignoring invalid DNS server '$dns'") }
        }

        if (settings.bypassLan) {
            Routes.PUBLIC_IPV4.forEach { (address, prefix) -> builder.addRoute(address, prefix) }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        if (settings.ipv6) {
            builder.addAddress(Tun2SocksConfig.TUN_ADDRESS_V6, Tun2SocksConfig.TUN_PREFIX_V6)
            builder.addRoute("::", 0)
        }

        applyAppRouting(builder, settings)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        builder.setBlocking(false)
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )

        return builder.establish()
    }

    /**
     * Excluding our own package is what keeps the proxy from talking to itself;
     * it is applied in every mode except [TunnelSettings.RoutingMode.ALLOW],
     * where simply not listing ourselves has the same effect.
     */
    private fun applyAppRouting(builder: Builder, settings: TunnelSettings) {
        val selected = settings.selectedApps - packageName

        when (settings.routingMode) {
            TunnelSettings.RoutingMode.ALLOW -> {
                if (selected.isEmpty()) {
                    log("per-app routing is set to allow-list but nothing is selected; routing every app instead")
                    excludeSelf(builder)
                    return
                }
                selected.forEach { pkg ->
                    try {
                        builder.addAllowedApplication(pkg)
                    } catch (_: PackageManager.NameNotFoundException) {
                        log("skipping uninstalled package $pkg")
                    }
                }
            }

            TunnelSettings.RoutingMode.DENY -> {
                excludeSelf(builder)
                selected.forEach { pkg ->
                    try {
                        builder.addDisallowedApplication(pkg)
                    } catch (_: PackageManager.NameNotFoundException) {
                        log("skipping uninstalled package $pkg")
                    }
                }
            }

            TunnelSettings.RoutingMode.ALL -> excludeSelf(builder)
        }
    }

    private fun excludeSelf(builder: Builder) {
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            // Cannot happen for our own package, but a broken build would loop
            // traffic back into the proxy, so it is worth shouting about.
            Log.e(TAG, "cannot exclude self from the VPN", e)
        }
    }

    // -----------------------------------------------------------------------
    // Shutdown
    // -----------------------------------------------------------------------

    private fun shutdown() {
        if (stage == TunnelStatus.Stage.STOPPED) {
            stopSelfAndProcess(killProcess = true)
            return
        }
        publish(TunnelStatus.Stage.STOPPING, getString(R.string.status_stopping))
        startJob?.cancel()
        scope.launch {
            teardown()
            publish(TunnelStatus.Stage.STOPPED, getString(R.string.status_stopped))
            stopSelfAndProcess(killProcess = true)
        }
    }

    /** Reverse order of startup; every step is independently failure-tolerant. */
    private suspend fun teardown() = withContext(Dispatchers.IO) {
        runCatching { Tun2SocksNative.stop() }.onFailure { Log.w(TAG, "tunnel stop failed", it) }
        runCatching { tunnel?.close() }.onFailure { Log.w(TAG, "tun close failed", it) }
        tunnel = null
        runCatching { MihomoKernel.stopBlocking() }
            .onFailure { Log.w(TAG, "kernel stop failed", it) }
        runCatching { MitmNative.stop() }.onFailure { Log.w(TAG, "MITM stop failed", it) }
        log("=== tunnel stopped ===")
    }

    /**
     * @param killProcess reclaims the Rust core's leaked settings and descriptor
     *        pool. Safe because nothing else lives in `:core`.
     */
    private fun stopSelfAndProcess(killProcess: Boolean) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (killProcess) {
            // Deliberately NOT on `scope`: stopSelf() leads to onDestroy(), which
            // cancels that scope, so a coroutine here would never fire and the
            // process would live on holding the Rust core's leaked settings.
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    // Give the status broadcast a moment to reach the UI process.
                    Process.killProcess(Process.myPid())
                },
                PROCESS_KILL_DELAY_MS,
            )
        }
    }

    override fun onRevoke() {
        log("VPN permission revoked by the system")
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        val wasActive = stage != TunnelStatus.Stage.STOPPED && stage != TunnelStatus.Stage.ERROR
        if (wasActive) {
            // Torn down without going through ACTION_STOP: still release natives.
            runCatching { Tun2SocksNative.stop() }
            runCatching { tunnel?.close() }
            runCatching { MihomoKernel.stopBlocking() }
            runCatching { MitmNative.stop() }
            publish(TunnelStatus.Stage.STOPPED, getString(R.string.status_stopped))
            // Reclaim the Rust core's leaked settings on this path as well;
            // nothing else lives in `:core`.
            Handler(Looper.getMainLooper()).postDelayed(
                { Process.killProcess(Process.myPid()) },
                PROCESS_KILL_DELAY_MS,
            )
        }
        scope.cancel()
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Notification + status
    // -----------------------------------------------------------------------

    private fun startForegroundNotification(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), type)
    }

    private fun updateNotification(text: String) {
        runCatching {
            NotificationHelper.notify(this, NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_running))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .addAction(
                0,
                getString(R.string.notif_stop),
                PendingIntent.getBroadcast(
                    this,
                    1,
                    Intent(TileActionReceiver.ACTION_STOP).setPackage(packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    private fun publish(
        newStage: TunnelStatus.Stage,
        message: String,
        modEnabled: Boolean = false,
        helperEnabled: Boolean = false,
    ) {
        stage = newStage
        TunnelStatus.publish(
            this,
            TunnelStatus(
                stage = newStage,
                message = message,
                mitmAddress = mitmAddress,
                mixedPort = mixedPort,
                startedAt = if (newStage == TunnelStatus.Stage.RUNNING) startedAt else 0L,
                modEnabled = modEnabled,
                helperEnabled = helperEnabled,
                proxyOnly = proxyOnly,
            ),
        )
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        LogStore.append(this, "svc: $line")
    }

    companion object {
        private const val TAG = "MajsoulVpn"
        private const val NOTIFICATION_ID = 0x4d41
        private const val MITM_START_TIMEOUT_MS = 120_000L
        private const val PROCESS_KILL_DELAY_MS = 400L

        const val ACTION_START = "moe.majsoulmax.app.action.START"
        const val EXTRA_PROXY_ONLY = "proxyOnly"
        const val ACTION_STOP = "moe.majsoulmax.app.action.SERVICE_STOP"
    }
}
