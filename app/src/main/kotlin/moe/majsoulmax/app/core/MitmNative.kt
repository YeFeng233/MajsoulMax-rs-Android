package moe.majsoulmax.app.core

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Kotlin side of the JNI bridge in `rust/majsoul-jni`.
 *
 * `nativeStart` is non-blocking: the Rust worker may spend time refreshing
 * protocol files over the network, so callers observe progress through [state]
 * and read [lastError] when it lands on [State.ERROR].
 */
object MitmNative {

    private const val TAG = "MitmNative"

    enum class State {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        ERROR,
        UNAVAILABLE,
        ;

        val isActive: Boolean get() = this == STARTING || this == RUNNING || this == STOPPING
    }

    /** False when libmajsoulmax.so is missing from the APK for this ABI. */
    val available: Boolean by lazy {
        try {
            System.loadLibrary("majsoulmax")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "libmajsoulmax.so unavailable", e)
            false
        }
    }

    val state: State
        get() = if (!available) {
            State.UNAVAILABLE
        } else {
            when (nativeState()) {
                0 -> State.STOPPED
                1 -> State.STARTING
                2 -> State.RUNNING
                3 -> State.STOPPING
                else -> State.ERROR
            }
        }

    val lastError: String?
        get() = if (available) {
            nativeLastError()?.takeIf { it.isNotBlank() }
        } else {
            "libmajsoulmax.so missing"
        }

    val version: String
        get() = (if (available) nativeVersion() else null) ?: "n/a"

    /**
     * @return null on success, or a human-readable reason.
     */
    fun start(context: Context): String? {
        if (!available) return "libmajsoulmax.so missing for this ABI"

        val configDir: File = Paths.configDir(context)
        val logFile: File = Paths.logFile(context)
        logFile.parentFile?.mkdirs()

        return if (nativeStart(configDir.absolutePath, logFile.absolutePath) == 0) {
            null
        } else {
            lastError ?: "unknown native error"
        }
    }

    fun stop() {
        if (available) nativeStop()
    }

    val isRunning: Boolean
        get() = available && nativeIsRunning()

    // Numeric states mirror the constants in rust/majsoul-jni/src/lib.rs.
    private external fun nativeStart(configDir: String, logFile: String): Int

    private external fun nativeStop()

    private external fun nativeState(): Int

    private external fun nativeIsRunning(): Boolean

    // Both return null only if the native string allocation fails.
    private external fun nativeLastError(): String?

    private external fun nativeVersion(): String?
}
