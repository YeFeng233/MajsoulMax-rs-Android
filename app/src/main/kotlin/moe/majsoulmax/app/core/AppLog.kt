package moe.majsoulmax.app.core

import android.content.Context
import android.os.Process
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val TAG = "AppLog"
    private const val MAX_BYTES = 2L * 1024 * 1024
    private val lock = Any()
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var context: Context? = null
    @Volatile private var installed = false

    fun install(value: Context) {
        context = value.applicationContext
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            e("crash", "uncaught exception on ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
        i("app", "process started pid=${Process.myPid()}")
    }

    fun i(source: String, message: String) = write("I", source, message, null)
    fun e(source: String, message: String, error: Throwable? = null) = write("E", source, message, error)

    private fun write(level: String, source: String, message: String, error: Throwable?) {
        val line = synchronized(lock) { buildString { append(format.format(Date())).append(' ').append(level).append('/').append(source).append(": ").append(message); if (error != null) append(" — ${error.javaClass.simpleName}: ${error.message}") } }
        when (level) { "E" -> Log.e(TAG, line, error); "W" -> Log.w(TAG, line, error); else -> Log.i(TAG, line) }
        val file = context?.let { Paths.appLogFile(it) } ?: return
        synchronized(lock) { runCatching { file.parentFile?.mkdirs(); if (file.length() > MAX_BYTES) file.writeText("--- app log truncated ---\n" + file.readText().takeLast((MAX_BYTES / 2).toInt())); file.appendText(line + "\n") }.onFailure { Log.w(TAG, "cannot write app log", it) } }
    }
}
