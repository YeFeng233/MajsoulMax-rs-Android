package moe.majsoulmax.app.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.majsoulmax.app.data.TunnelSettings
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Supervises the bundled Meta (mihomo) kernel.
 *
 * The kernel ships as `libmihomo.so` inside `jniLibs` — the only directory an app
 * may execute from on modern Android — and is driven as a child process through
 * its documented `-d`/`-f` CLI and YAML config. Talking to it over its stable
 * config surface rather than linking its Go internals means a kernel bump is a
 * file swap, not a code change.
 *
 * Routing model: the kernel listens on a mixed (SOCKS5 + HTTP) port, sends
 * Mahjong Soul domains into the local MITM proxy and everything else DIRECT.
 * Loopback is prevented one level up, by excluding this app from the VPN, so no
 * PROCESS-NAME rule is needed here.
 */
object MihomoKernel {

    private const val TAG = "MihomoKernel"
    private const val STARTUP_TIMEOUT_MS = 15_000L
    private const val POLL_INTERVAL_MS = 150L

    @Volatile
    private var process: Process? = null

    @Volatile
    private var logPump: Thread? = null

    fun isBundled(context: Context): Boolean = Paths.mihomoBinary(context).canExecute()

    val isRunning: Boolean
        get() = process?.isAlive == true

    /**
     * Writes the config and starts the kernel, returning only once the mixed port
     * accepts connections.
     *
     * @return null on success, otherwise a human-readable reason.
     */
    suspend fun start(
        context: Context,
        settings: TunnelSettings,
        mitmHost: String,
        mitmPort: Int,
    ): String? = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext null

        val binary = Paths.mihomoBinary(context)
        if (!binary.exists()) {
            return@withContext "libmihomo.so is not bundled for this ABI " +
                "(expected at ${binary.absolutePath})"
        }
        if (!binary.canExecute()) {
            // Happens when the APK was built without useLegacyPackaging, so the
            // kernel was never extracted to disk.
            return@withContext "libmihomo.so is not executable; the APK must be " +
                "built with jniLibs.useLegacyPackaging = true"
        }

        val home = Paths.kernelHome(context).apply { mkdirs() }
        val configFile = Paths.kernelConfig(context)

        try {
            configFile.writeText(
                buildConfig(settings, mitmHost, mitmPort),
            )
        } catch (e: IOException) {
            return@withContext "cannot write ${configFile.name}: ${e.message}"
        }

        val started = try {
            ProcessBuilder(binary.absolutePath, "-d", home.absolutePath, "-f", configFile.absolutePath)
                .directory(home)
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            Log.e(TAG, "cannot exec the Meta kernel", e)
            return@withContext "cannot exec the Meta kernel: ${e.message}"
        }

        process = started
        logPump = pumpOutput(started, Paths.logFile(context))

        val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!started.isAlive) {
                val code = started.exitValue()
                process = null
                return@withContext "the Meta kernel exited immediately with code $code — see the log"
            }
            if (portAccepting(settings.mixedPort)) {
                Log.i(TAG, "Meta kernel is listening on ${settings.mixedPort}")
                return@withContext null
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }

        stopBlocking()
        "the Meta kernel did not open port ${settings.mixedPort} within " +
            "${STARTUP_TIMEOUT_MS / 1000}s — see the log"
    }

    suspend fun stop() = withContext(Dispatchers.IO) { stopBlocking() }

    fun stopBlocking() {
        val current = process ?: return
        process = null
        runCatching {
            current.destroy()
            if (!current.waitFor(3, TimeUnit.SECONDS)) {
                current.destroyForcibly()
                current.waitFor(2, TimeUnit.SECONDS)
            }
        }.onFailure { Log.w(TAG, "error while stopping the Meta kernel", it) }
        logPump?.interrupt()
        logPump = null
        Log.i(TAG, "Meta kernel stopped")
    }

    private fun portAccepting(port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 400)
            true
        }
    } catch (_: Exception) {
        false
    }

    /** Folds kernel stdout/stderr into the shared log the UI tails. */
    private fun pumpOutput(process: Process, logFile: File): Thread {
        val thread = Thread({
            try {
                logFile.parentFile?.mkdirs()
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (Thread.currentThread().isInterrupted) return@forEach
                        Log.i(TAG, line)
                        runCatching { logFile.appendText("meta: $line\n") }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "kernel log pump ended: ${e.message}")
            }
        }, "mihomo-log")
        thread.isDaemon = true
        thread.start()
        return thread
    }

    /**
     * Builds the kernel config.
     *
     * Two details carry the whole design:
     *
     * * `sniffer.override-destination` — the tun gives us IP packets, so without
     *   recovering the hostname from the TLS handshake the DOMAIN-KEYWORD rules
     *   below could never match.
     * * the MITM proxy is a plain `http` outbound with `udp: false`, matching how
     *   upstream documents itself for Clash and Surge.
     */
    fun buildConfig(settings: TunnelSettings, mitmHost: String, mitmPort: Int): String {
        val s = settings.sanitised()
        // An empty keyword list would route nothing into the proxy, i.e. silently
        // disable the whole point of the app, so fall back to the defaults here.
        val keywords = s.domainKeywords.ifEmpty { TunnelSettings.DEFAULT_KEYWORDS }
        val rules = buildList {
            addAll(s.extraRules)
            keywords.forEach { add("DOMAIN-KEYWORD,$it,$PROXY_GROUP") }
            add("MATCH,DIRECT")
        }

        return buildString {
            appendLine("# Generated by Majsoul Max — edits here are overwritten on every start.")
            appendLine("mixed-port: ${s.mixedPort}")
            appendLine("allow-lan: false")
            appendLine("bind-address: 127.0.0.1")
            appendLine("mode: rule")
            appendLine("log-level: ${s.kernelLogLevel}")
            appendLine("ipv6: ${s.ipv6}")
            appendLine("unified-delay: false")
            appendLine("tcp-concurrent: false")
            appendLine("find-process-mode: off")
            appendLine("global-client-fingerprint: chrome")
            appendLine("external-controller: ''")
            appendLine("geo-auto-update: false")
            appendLine("profile:")
            appendLine("  store-selected: false")
            appendLine("  store-fake-ip: false")
            appendLine("dns:")
            // DNS stays with the system resolver reached through the tunnel; the
            // kernel resolving names as well would only add a second cache.
            appendLine("  enable: false")
            appendLine("sniffer:")
            appendLine("  enable: ${s.sniff}")
            appendLine("  override-destination: true")
            appendLine("  force-dns-mapping: true")
            appendLine("  parse-pure-ip: true")
            appendLine("  sniff:")
            appendLine("    TLS:")
            appendLine("      ports: [443, 8443]")
            appendLine("    HTTP:")
            appendLine("      ports: [80, 8080, 8880]")
            appendLine("proxies:")
            appendLine("  - name: $PROXY_NAME")
            appendLine("    type: http")
            appendLine("    server: ${yamlString(mitmHost)}")
            appendLine("    port: $mitmPort")
            appendLine("    tls: false")
            appendLine("    udp: false")
            appendLine("proxy-groups:")
            appendLine("  - name: $PROXY_GROUP")
            appendLine("    type: select")
            appendLine("    proxies:")
            appendLine("      - $PROXY_NAME")
            appendLine("      - DIRECT")
            appendLine("rules:")
            rules.forEach { appendLine("  - ${yamlString(it)}") }
        }
    }

    /**
     * Single-quoted YAML scalar. Safe for every value we emit (hosts, keywords,
     * rule lines) and immune to a keyword like `no` being read as a boolean.
     */
    private fun yamlString(value: String): String = "'" + value.replace("'", "''") + "'"

    private const val PROXY_NAME = "MajsoulMax"
    private const val PROXY_GROUP = "MAJSOUL"
}
