package moe.majsoulmax.app.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.majsoulmax.app.core.Paths
import java.io.File

/**
 * Tunnel state as published by the `:core` process and consumed by the UI.
 *
 * Transport is a small JSON file plus a targeted broadcast that says "re-read it".
 * The file is the source of truth, so the UI shows the right thing even if it was
 * not running when the state last changed — which a bound service or a
 * broadcast-only design would both get wrong.
 */
@Serializable
data class TunnelStatus(
    @SerialName("stage") val stage: Stage = Stage.STOPPED,
    @SerialName("message") val message: String = "",
    @SerialName("mitmAddress") val mitmAddress: String = "",
    @SerialName("mixedPort") val mixedPort: Int = 0,
    @SerialName("startedAt") val startedAt: Long = 0L,
    @SerialName("mod") val modEnabled: Boolean = false,
    @SerialName("helper") val helperEnabled: Boolean = false,
    /** True when only the MITM proxy runs, with no tun and no Meta kernel. */
    @SerialName("proxyOnly") val proxyOnly: Boolean = false,
) {
    enum class Stage {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        ERROR,
        ;

        val isBusy: Boolean get() = this == STARTING || this == STOPPING
        val isOn: Boolean get() = this == STARTING || this == RUNNING
    }

    val uptimeMillis: Long
        get() = if (stage == Stage.RUNNING && startedAt > 0) {
            (System.currentTimeMillis() - startedAt).coerceAtLeast(0)
        } else {
            0
        }

    companion object {
        const val ACTION_STATUS_CHANGED = "moe.majsoulmax.app.action.STATUS"

        private const val TAG = "TunnelStatus"

        private val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun read(context: Context): TunnelStatus {
            val file: File = Paths.statusFile(context)
            return try {
                if (file.exists()) JSON.decodeFromString(file.readText()) else TunnelStatus()
            } catch (e: Exception) {
                Log.w(TAG, "status.json unreadable", e)
                TunnelStatus()
            }
        }

        /** Called from `:core` only. */
        fun publish(context: Context, status: TunnelStatus) {
            try {
                val file = Paths.statusFile(context)
                file.parentFile?.mkdirs()
                file.writeText(JSON.encodeToString(status))
            } catch (e: Exception) {
                Log.w(TAG, "cannot persist status.json", e)
            }
            // Scoped to our own package so this never leaves the app.
            context.sendBroadcast(
                Intent(ACTION_STATUS_CHANGED).setPackage(context.packageName),
            )
        }

        /**
         * Current status, then a fresh read on every change published by `:core`.
         */
        fun observe(context: Context): Flow<TunnelStatus> = callbackFlow {
            trySend(read(context))

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    trySend(read(context))
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(ACTION_STATUS_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )

            awaitClose { runCatching { context.unregisterReceiver(receiver) } }
        }
    }
}
