package moe.majsoulmax.app.core

import android.content.Context
import java.io.File

/**
 * Single source of truth for on-disk layout. Both the UI process and the `:core`
 * process resolve paths through here, so there is exactly one place where the
 * layout is defined.
 */
object Paths {

    /** Upstream's `liqi_config` directory, handed to `Settings::new` verbatim. */
    fun configDir(context: Context): File = File(context.filesDir, "liqi_config")

    fun settingsJson(context: Context): File = File(configDir(context), "settings.json")

    fun modSettingsJson(context: Context): File = File(configDir(context), "settings.mod.json")

    fun liqiJson(context: Context): File = File(configDir(context), "liqi.json")

    fun lqcBin(context: Context): File = File(configDir(context), "lqc.lqbin")

    /** The hudsucker root certificate the user has to trust. */
    fun certFile(context: Context): File = File(context.filesDir, "ca/hudsucker.cer")

    /** Working directory handed to the Meta kernel via `-d`. */
    fun kernelHome(context: Context): File = File(context.filesDir, "run")

    fun kernelConfig(context: Context): File = File(kernelHome(context), "config.yaml")

    fun logDir(context: Context): File = File(context.filesDir, "logs")

    /** Shared log sink: Rust core, Meta kernel and Kotlin all append here. */
    fun logFile(context: Context): File = File(logDir(context), "core.log")

    /** Cross-process tunnel state, written by `:core` and read by the UI. */
    fun statusFile(context: Context): File = File(context.filesDir, "status.json")

    /** Marker recording which app version last unpacked the bundled assets. */
    fun assetStamp(context: Context): File = File(context.filesDir, ".assets-version")

    /** Native payloads are executed from here, the only exec-permitted location. */
    fun nativeLibDir(context: Context): File = File(context.applicationInfo.nativeLibraryDir)

    fun mihomoBinary(context: Context): File = File(nativeLibDir(context), "libmihomo.so")

    fun ensureDirectories(context: Context) {
        listOf(configDir(context), File(context.filesDir, "ca"), kernelHome(context), logDir(context))
            .forEach { it.mkdirs() }
    }
}
