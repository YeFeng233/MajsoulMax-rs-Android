package moe.majsoulmax.app.core

import android.util.Log

/**
 * Bridges the VpnService tun descriptor to the Meta kernel's SOCKS5 port through
 * hev-socks5-tunnel (see `app/src/main/cpp/tun2socks_jni.c`).
 *
 * The prebuilt tunnel library is optional at build time, so [available] can be
 * false in a perfectly well-formed APK; callers must surface that rather than
 * assume it.
 */
object Tun2SocksNative {

    private const val TAG = "Tun2Socks"

    private const val OK = 0
    private const val ALREADY_RUNNING = -1
    private const val BAD_ARGUMENT = -2
    private const val THREAD_FAILED = -3
    private const val UNAVAILABLE = -4

    private val loaded: Boolean by lazy {
        try {
            System.loadLibrary("tun2socks")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "libtun2socks.so unavailable", e)
            false
        }
    }

    val available: Boolean
        get() = loaded && nativeIsAvailable()

    /**
     * @param config hev-socks5-tunnel YAML, see [Tun2SocksConfig].
     * @param tunFd descriptor from `VpnService.Builder.establish()`; ownership
     *        stays with the caller, which must keep the [android.os.ParcelFileDescriptor]
     *        alive for as long as the tunnel runs.
     * @return null on success, otherwise a human-readable reason.
     */
    fun start(config: String, tunFd: Int): String? {
        if (!loaded) return "libtun2socks.so missing for this ABI"
        return when (val rc = nativeStart(config, tunFd)) {
            OK -> null
            ALREADY_RUNNING -> "tunnel already running"
            BAD_ARGUMENT -> "invalid tunnel arguments (fd=$tunFd)"
            THREAD_FAILED -> "could not spawn the tunnel thread"
            UNAVAILABLE ->
                "this build has no hev-socks5-tunnel payload; run ./scripts/build-tun2socks.sh"
            else -> "tunnel failed with code $rc"
        }
    }

    fun stop() {
        if (loaded) nativeStop()
    }

    val isRunning: Boolean
        get() = loaded && nativeIsRunning()

    private external fun nativeStart(config: String, tunFd: Int): Int

    private external fun nativeStop()

    private external fun nativeIsRunning(): Boolean

    private external fun nativeIsAvailable(): Boolean
}
