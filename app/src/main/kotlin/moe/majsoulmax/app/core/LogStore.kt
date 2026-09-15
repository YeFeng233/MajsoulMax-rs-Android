package moe.majsoulmax.app.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Tails the shared log file the Rust core, the Meta kernel and the Kotlin service
 * all append to.
 *
 * A file is the transport on purpose: the tunnel lives in the `:core` process, so
 * an in-memory buffer would be invisible to the UI, and a file needs no IPC,
 * survives a `:core` restart, and can be shared straight out to a bug report.
 */
object LogStore {

    private const val TAG = "LogStore"
    private const val MAX_TAIL_BYTES = 512L * 1024
    private const val POLL_INTERVAL_MS = 400L

    /** Hard cap so a chatty debug session cannot fill the user's storage. */
    private const val ROTATE_ABOVE_BYTES = 4L * 1024 * 1024

    fun append(context: Context, line: String) {
        try {
            val file = Paths.logFile(context)
            file.parentFile?.mkdirs()
            file.appendText(line.trimEnd() + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "cannot append to the log", e)
        }
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        runCatching { Paths.logFile(context).writeText("") }
        Unit
    }

    suspend fun readAll(context: Context): String = withContext(Dispatchers.IO) {
        val file = Paths.logFile(context)
        if (file.exists()) tailOf(file, MAX_TAIL_BYTES) else ""
    }

    /** Trims the log when it grows past [ROTATE_ABOVE_BYTES], keeping the tail. */
    fun rotateIfNeeded(context: Context) {
        try {
            val file = Paths.logFile(context)
            if (file.exists() && file.length() > ROTATE_ABOVE_BYTES) {
                val keep = tailOf(file, MAX_TAIL_BYTES)
                file.writeText("--- log truncated ---\n$keep")
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot rotate the log", e)
        }
    }

    /**
     * Emits the current tail, then every appended chunk.
     *
     * Truncation is detected by the file shrinking, at which point the stream
     * restarts from the beginning rather than reading garbage at a stale offset.
     */
    fun tail(context: Context): Flow<String> = flow {
        val file = Paths.logFile(context)
        var offset = 0L

        emit(if (file.exists()) tailOf(file, MAX_TAIL_BYTES).also { offset = file.length() } else "")

        while (true) {
            kotlinx.coroutines.delay(POLL_INTERVAL_MS)
            if (!file.exists()) {
                if (offset != 0L) {
                    offset = 0L
                    emit("")
                }
                continue
            }
            val length = file.length()
            when {
                length < offset -> {
                    offset = 0L
                    emit(tailOf(file, MAX_TAIL_BYTES))
                    offset = file.length()
                }
                length > offset -> {
                    val chunk = readFrom(file, offset)
                    offset = length
                    if (chunk.isNotEmpty()) emit(chunk)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun tailOf(file: File, maxBytes: Long): String = try {
        RandomAccessFile(file, "r").use { raf ->
            val start = (raf.length() - maxBytes).coerceAtLeast(0L)
            raf.seek(start)
            val bytes = ByteArray((raf.length() - start).toInt())
            raf.readFully(bytes)
            String(bytes).let { if (start > 0) it.substringAfter('\n', it) else it }
        }
    } catch (e: Exception) {
        Log.w(TAG, "cannot read the log", e)
        ""
    }

    private fun readFrom(file: File, offset: Long): String = try {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val bytes = ByteArray((raf.length() - offset).toInt())
            raf.readFully(bytes)
            String(bytes)
        }
    } catch (e: Exception) {
        Log.w(TAG, "cannot read the log tail", e)
        ""
    }
}
