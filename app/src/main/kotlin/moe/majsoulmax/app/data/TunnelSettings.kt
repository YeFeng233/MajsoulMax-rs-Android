package moe.majsoulmax.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * App-level tunnel preferences: everything about *how* traffic reaches the MITM
 * proxy, as opposed to upstream's own `settings.json`, which governs what the
 * proxy does once it has the traffic.
 *
 * Deliberately a plain JSON file rather than DataStore: the tunnel runs in the
 * `:core` process and DataStore is not multi-process safe. The UI is the only
 * writer and `:core` only ever reads, so a single atomic file is both sufficient
 * and easy to reason about.
 */
@Serializable
data class TunnelSettings(
    @SerialName("routingMode") val routingMode: RoutingMode = RoutingMode.ALL,
    @SerialName("selectedApps") val selectedApps: Set<String> = emptySet(),
    @SerialName("mixedPort") val mixedPort: Int = DEFAULT_MIXED_PORT,
    @SerialName("mtu") val mtu: Int = DEFAULT_MTU,
    @SerialName("bypassLan") val bypassLan: Boolean = true,
    @SerialName("ipv6") val ipv6: Boolean = false,
    @SerialName("sniff") val sniff: Boolean = true,
    @SerialName("upstreamDns") val upstreamDns: List<String> = DEFAULT_DNS,
    @SerialName("domainKeywords") val domainKeywords: List<String> = DEFAULT_KEYWORDS,
    @SerialName("extraRules") val extraRules: List<String> = emptyList(),
    @SerialName("kernelLogLevel") val kernelLogLevel: String = "warning",
    @SerialName("acceptedDisclaimer") val acceptedDisclaimer: Boolean = false,
) {
    enum class RoutingMode {
        /** Every app except this one. */
        ALL,

        /** Only [selectedApps]. */
        ALLOW,

        /** Everything except [selectedApps] (and this one). */
        DENY,
    }

    /**
     * Clamps anything a hand-edited file could get wrong.
     *
     * Deliberately does *not* substitute defaults for empty lists: doing so while
     * the user is editing would resurrect entries they just deleted. The defaults
     * are applied where the values are consumed instead.
     */
    fun sanitised(): TunnelSettings = copy(
        mixedPort = mixedPort.coerceIn(1024, 65535),
        mtu = mtu.coerceIn(1280, 9000),
        upstreamDns = upstreamDns.map { it.trim() }.filter { it.isNotEmpty() },
        domainKeywords = domainKeywords.map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct(),
        extraRules = extraRules.map { it.trim() }.filter { it.isNotEmpty() },
        kernelLogLevel = kernelLogLevel.takeIf { it in LOG_LEVELS } ?: "warning",
    )

    companion object {
        const val DEFAULT_MIXED_PORT = 7890
        const val DEFAULT_MTU = 8500

        val DEFAULT_DNS = listOf("223.5.5.5", "119.29.29.29")

        /**
         * Mirrors the DOMAIN-KEYWORD rules upstream documents for Clash. Kept as
         * keywords rather than exact hosts because Mahjong Soul serves several
         * regional domains.
         */
        val DEFAULT_KEYWORDS = listOf("majsoul", "maj-soul", "mahjongsoul")

        val LOG_LEVELS = listOf("silent", "error", "warning", "info", "debug")
    }
}

/**
 * Process-wide store. It must be a singleton: `update` rewrites the whole file
 * from an in-memory snapshot, so two instances would each overwrite the other's
 * changes with their own stale copy.
 */
class TunnelSettingsStore private constructor(private val context: Context) {

    private val file: File get() = File(context.filesDir, "tunnel.json")

    private val _settings = MutableStateFlow(loadBlocking())
    val settings: StateFlow<TunnelSettings> = _settings.asStateFlow()

    fun loadBlocking(): TunnelSettings = try {
        if (file.exists()) {
            JSON.decodeFromString<TunnelSettings>(file.readText()).sanitised()
        } else {
            TunnelSettings()
        }
    } catch (e: Exception) {
        Log.e(TAG, "tunnel.json unreadable, falling back to defaults", e)
        TunnelSettings()
    }

    suspend fun update(transform: (TunnelSettings) -> TunnelSettings) {
        val next = transform(_settings.value).sanitised()
        _settings.value = next
        withContext(Dispatchers.IO) { writeBlocking(next) }
    }

    private fun writeBlocking(value: TunnelSettings) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "tunnel.json.tmp")
            tmp.writeText(JSON.encodeToString(value))
            if (!tmp.renameTo(file)) {
                file.writeText(JSON.encodeToString(value))
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "cannot persist tunnel.json", e)
        }
    }

    companion object {
        private const val TAG = "TunnelSettings"

        private val JSON = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        @Volatile
        private var instance: TunnelSettingsStore? = null

        fun get(context: Context): TunnelSettingsStore =
            instance ?: synchronized(this) {
                instance ?: TunnelSettingsStore(context.applicationContext).also { instance = it }
            }

        /** For the `:core` process, which reads once at startup and never writes. */
        fun read(context: Context): TunnelSettings = get(context).loadBlocking()
    }
}
