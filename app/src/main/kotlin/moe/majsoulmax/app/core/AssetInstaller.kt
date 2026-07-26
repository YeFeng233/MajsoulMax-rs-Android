package moe.majsoulmax.app.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.majsoulmax.app.BuildConfig
import java.io.File
import java.io.IOException

/**
 * Unpacks the `liqi_config` bundle and the MITM root certificate from APK assets
 * into app storage, because the Rust core reads them as ordinary files.
 *
 * Upgrade policy matters here: protocol data is refreshed on every app upgrade so
 * a new APK ships a working `liqi.json`/`lqc.lqbin`, while the two settings files
 * are only ever created if absent — a user's tuning survives upgrades.
 */
object AssetInstaller {

    private const val TAG = "AssetInstaller"

    /** Refreshed on every app upgrade. */
    private val DATA_FILES = listOf("liqi.json", "lqc.lqbin", "liqi.desc")

    /** Created once, then owned by the user. */
    private val USER_FILES = listOf("settings.json", "settings.mod.json")

    data class Result(val installed: Boolean, val error: String? = null) {
        val ok: Boolean get() = error == null
    }

    fun isInstalled(context: Context): Boolean =
        Paths.settingsJson(context).exists() &&
            Paths.liqiJson(context).exists() &&
            Paths.lqcBin(context).exists() &&
            Paths.certFile(context).exists()

    suspend fun ensure(context: Context, force: Boolean = false): Result =
        withContext(Dispatchers.IO) { ensureBlocking(context, force) }

    fun ensureBlocking(context: Context, force: Boolean = false): Result {
        return try {
            Paths.ensureDirectories(context)

            val stamp = Paths.assetStamp(context)
            val current = BuildConfig.VERSION_NAME + "/" + BuildConfig.VERSION_CODE
            val upgraded = force || !stamp.exists() || stamp.readText().trim() != current

            val configDir = Paths.configDir(context)
            var wrote = false

            DATA_FILES.forEach { name ->
                val target = File(configDir, name)
                if (upgraded || !target.exists()) {
                    copyAsset(context, "liqi_config/$name", target)
                    wrote = true
                }
            }

            USER_FILES.forEach { name ->
                val target = File(configDir, name)
                if (!target.exists()) {
                    copyAsset(context, "liqi_config/$name", target)
                    wrote = true
                }
            }

            val cert = Paths.certFile(context)
            if (upgraded || !cert.exists()) {
                copyAsset(context, "ca/hudsucker.cer", cert)
                wrote = true
            }

            if (upgraded) stamp.writeText(current)
            Result(installed = wrote)
        } catch (e: IOException) {
            Log.e(TAG, "asset install failed", e)
            Result(installed = false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Restores one bundled default, discarding local edits. Used by the config
     * editor's reset action.
     */
    fun restoreDefault(context: Context, name: String) {
        Paths.ensureDirectories(context)
        copyAsset(context, "liqi_config/$name", File(Paths.configDir(context), name))
    }

    fun readBundledDefault(context: Context, name: String): String =
        context.assets.open("liqi_config/$name").bufferedReader().use { it.readText() }

    private fun copyAsset(context: Context, assetPath: String, target: File) {
        target.parentFile?.mkdirs()
        // Write to a sibling first so a crash mid-copy cannot leave the core
        // reading a truncated liqi.json.
        val tmp = File(target.parentFile, target.name + ".tmp")
        context.assets.open(assetPath).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        Log.i(TAG, "installed $assetPath -> ${target.name} (${target.length()} bytes)")
    }
}
