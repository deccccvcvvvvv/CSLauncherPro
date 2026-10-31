package pro.cslauncher.core.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Shared JSON configuration.
 *
 * Network metadata (Mojang, Fabric, Modrinth) is **untrusted input** that gains fields over time,
 * so unknown keys are ignored instead of failing. Structural problems still raise
 * [MetadataException] through [parseJsonObject], because silently accepting a broken manifest is
 * worse than telling the user what is wrong.
 */
val CsJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    explicitNulls = false
    classDiscriminator = "type"
}

/** Same settings, compact output: used for files written on every launch. */
val CsJsonCompact: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
    explicitNulls = false
    classDiscriminator = "type"
}

/** A remote payload was structurally wrong. Message is always user-presentable. */
class MetadataException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun parseJsonObject(text: String, what: String): JsonObject = try {
    CsJson.parseToJsonElement(text) as? JsonObject ?: throw MetadataException("$what is not a JSON object")
} catch (e: MetadataException) {
    throw e
} catch (e: Exception) {
    throw MetadataException("$what is not valid JSON: ${e.message}", e)
}

fun parseJsonArray(text: String, what: String): JsonArray = try {
    CsJson.parseToJsonElement(text) as? JsonArray ?: throw MetadataException("$what is not a JSON array")
} catch (e: MetadataException) {
    throw e
} catch (e: Exception) {
    throw MetadataException("$what is not valid JSON: ${e.message}", e)
}

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** Some metadata encodes numbers as strings ("size": "1234"); both forms are accepted. */
fun JsonObject.numberish(key: String): Long? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

fun JsonObject.stringList(key: String): List<String> =
    arr(key)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

fun JsonPrimitive?.stringOrNull(): String? = this?.takeIf { it.isString }?.content

/** Reads a JSON object value as a flat string map, skipping nested structures. */
fun JsonObject.stringMap(key: String): Map<String, String> =
    obj(key)?.entries?.mapNotNull { (k, v) ->
        (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
    }?.toMap() ?: emptyMap()
