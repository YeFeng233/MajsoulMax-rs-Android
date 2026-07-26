package moe.majsoulmax.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import moe.majsoulmax.app.core.AssetInstaller
import moe.majsoulmax.app.core.Paths
import java.io.File

/**
 * Read/write access to upstream's `settings.json` and `settings.mod.json`.
 *
 * These files are deserialised by Rust into structs that require every field, and
 * they carry fields no UI will ever model (`viewsPresets` is an array of protobuf
 * `ViewSlot`s). So the editor works on [JsonObject]s and saves *patches* rather
 * than round-tripping through Kotlin data classes: unknown keys are preserved
 * byte-for-byte, and a field this app has never heard of cannot be dropped.
 *
 * Patching also avoids clobbering the Rust side, which rewrites `liqiVersion` and
 * `version` after a successful protocol update — a whole-file write from a stale
 * in-memory copy would silently revert it.
 */
class ConfigRepository(private val context: Context) {

    enum class Which(val fileName: String) {
        GENERAL("settings.json"),
        MOD("settings.mod.json"),
    }

    private fun fileOf(which: Which): File = File(Paths.configDir(context), which.fileName)

    suspend fun load(which: Which): JsonObject = withContext(Dispatchers.IO) {
        val file = fileOf(which)
        val text = when {
            file.exists() -> file.readText()
            else -> AssetInstaller.readBundledDefault(context, which.fileName)
        }
        JSON.parseToJsonElement(text).jsonObject
    }

    suspend fun loadRaw(which: Which): String = withContext(Dispatchers.IO) {
        val file = fileOf(which)
        if (file.exists()) {
            file.readText()
        } else {
            AssetInstaller.readBundledDefault(context, which.fileName)
        }
    }

    /**
     * Applies [patch] on top of whatever is on disk right now.
     *
     * @return the merged object that was written.
     */
    suspend fun patch(which: Which, patch: Map<String, JsonElement>): JsonObject =
        withContext(Dispatchers.IO) {
            val current = load(which)
            val merged = JsonObject(current.toMutableMap().apply { putAll(patch) })
            writeAtomically(fileOf(which), PRETTY.encodeToString(JsonObject.serializer(), merged))
            merged
        }

    /** Whole-file replacement, for the raw editor. Validate first with [validate]. */
    suspend fun writeRaw(which: Which, text: String): Unit = withContext(Dispatchers.IO) {
        val normalised = PRETTY.encodeToString(
            JsonObject.serializer(),
            JSON.parseToJsonElement(text).jsonObject,
        )
        writeAtomically(fileOf(which), normalised)
    }

    suspend fun reset(which: Which): JsonObject = withContext(Dispatchers.IO) {
        AssetInstaller.restoreDefault(context, which.fileName)
        load(which)
    }

    /** @return null when [text] is a valid JSON object, else the parser message. */
    fun validate(text: String): String? = try {
        val element = JSON.parseToJsonElement(text)
        if (element is JsonObject) null else "top level value must be an object"
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    fun prettyPrint(text: String): String =
        PRETTY.encodeToString(JsonObject.serializer(), JSON.parseToJsonElement(text).jsonObject)

    private fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = false }

        private val PRETTY = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

        /**
         * Splits upstream's `proxyAddr` into host and port for the kernel config.
         * Falls back to upstream's documented default so a malformed value cannot
         * stop the tunnel from coming up.
         */
        fun mitmEndpoint(settings: JsonObject): Pair<String, Int> {
            val raw = settings.string("proxyAddr", DEFAULT_MITM_ADDRESS).trim()
            val host = raw.substringBeforeLast(':', "").ifEmpty { "127.0.0.1" }
            val port = raw.substringAfterLast(':', "").toIntOrNull() ?: DEFAULT_MITM_PORT
            // The kernel and the tunnel both live in this app's network namespace,
            // so a wildcard bind is still reachable on loopback.
            val dialHost = if (host == "0.0.0.0" || host == "::" || host.isEmpty()) {
                "127.0.0.1"
            } else {
                host.removeSurrounding("[", "]")
            }
            return dialHost to port
        }

        const val DEFAULT_MITM_ADDRESS = "127.0.0.1:23410"
        const val DEFAULT_MITM_PORT = 23410
    }
}

// ---------------------------------------------------------------------------
// Typed views over JsonObject. Reads are forgiving because the file may have been
// hand-edited; writes always produce well-typed JSON.
// ---------------------------------------------------------------------------

fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

fun JsonObject.int(key: String, default: Int = 0): Int =
    (this[key] as? JsonPrimitive)?.intOrNull ?: default

fun JsonObject.string(key: String, default: String = ""): String {
    val primitive = this[key] as? JsonPrimitive ?: return default
    if (!primitive.isString && primitive.content == "null") return default
    return primitive.content
}

fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

fun JsonObject.intList(key: String): List<Int> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList()

/** For `charSkin`, whose keys are stringified character IDs. */
fun JsonObject.intMap(key: String): Map<Int, Int> {
    val obj = this[key] as? JsonObject ?: return emptyMap()
    return obj.entries.mapNotNull { (k, v) ->
        val id = k.toIntOrNull() ?: return@mapNotNull null
        val skin = (v as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        id to skin
    }.toMap()
}

fun jsonOf(value: Boolean): JsonElement = JsonPrimitive(value)

fun jsonOf(value: Int): JsonElement = JsonPrimitive(value)

fun jsonOf(value: String): JsonElement = JsonPrimitive(value)

fun jsonOfStrings(values: List<String>): JsonElement = JsonArray(values.map { JsonPrimitive(it) })

fun jsonOfInts(values: List<Int>): JsonElement = JsonArray(values.map { JsonPrimitive(it) })

fun jsonOfIntMap(values: Map<Int, Int>): JsonElement = buildJsonObject {
    values.toSortedMap().forEach { (k, v) -> put(k.toString(), JsonPrimitive(v)) }
}

/** Upstream models `reqProxy` as `Option<Url>`, so empty must serialise as null. */
fun jsonOfNullableString(value: String): JsonElement =
    if (value.isBlank()) kotlinx.serialization.json.JsonNull else JsonPrimitive(value.trim())

/** Reads a value that may be JSON null, presenting it as an empty string. */
fun JsonObject.nullableString(key: String): String {
    val element = this[key] ?: return ""
    if (element is JsonPrimitive) {
        return if (element.content == "null" && !element.isString) "" else element.content
    }
    return ""
}
