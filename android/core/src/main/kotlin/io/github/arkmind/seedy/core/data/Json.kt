package io.github.arkmind.seedy.core.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/* Fields of the objects Jellyfin hands over as raw JSON (policies, configurations, library options, items). */

fun JsonObject.bool(key: String, default: Boolean = false): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: default
fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
fun JsonObject.int(key: String, default: Int = 0): Int = intOrNull(key) ?: default
fun JsonObject.long(key: String, default: Long = 0): Long = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: default
fun JsonObject.double(key: String, default: Double = 0.0): Double = (this[key] as? JsonPrimitive)?.doubleOrNull ?: default
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())
fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

fun json(v: Boolean): JsonElement = JsonPrimitive(v)
fun json(v: Number): JsonElement = JsonPrimitive(v)
fun json(v: String?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)
fun json(v: List<String>): JsonElement = JsonArray(v.map { JsonPrimitive(it) })

/** A copy with [key] set. */
fun JsonObject.with(key: String, value: JsonElement): JsonObject = JsonObject(this + (key to value))
