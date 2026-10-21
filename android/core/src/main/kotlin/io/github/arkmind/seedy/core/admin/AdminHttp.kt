package io.github.arkmind.seedy.core.admin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod

/*
 * The server pages talk to Jellyfin's admin API the way the web dashboard does (web/src/screens/server/api.ts): the
 * same paths, read into small models of their own, and settings written back as the whole object Jellyfin sent with
 * only the changed keys replaced. The SDK's generated models would drop whatever this SDK version doesn't know, and
 * saving them would reset those settings on the server.
 */

/** Jellyfin's JSON is PascalCase; the models here are camelCase and read leniently (unknown keys and values pass). */
@OptIn(ExperimentalSerializationApi::class)
val JellyfinJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    isLenient = true
    encodeDefaults = true
    namingStrategy = object : JsonNamingStrategy {
        override fun serialNameForJson(descriptor: SerialDescriptor, elementIndex: Int, serialName: String): String =
            serialName.replaceFirstChar { it.uppercaseChar() }
    }
}

/** The Seedy plugin's JSON is camelCase already. */
val SeedyJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    isLenient = true
    encodeDefaults = true
}

/** An admin call the server refused or couldn't answer; [message] is what to tell the person. */
class AdminException(val status: Int, message: String) : Exception(message)

/** Raw calls through the signed-in client: its address, token and device headers. */
class AdminHttp(val api: ApiClient) {
    suspend fun raw(method: HttpMethod, path: String, query: Map<String, Any?> = emptyMap(), body: Any? = null): ByteArray = withContext(Dispatchers.IO) {
        val response = try {
            api.request(method, path, emptyMap(), query.filterValues { it != null }, body)
        } catch (e: org.jellyfin.sdk.api.client.exception.InvalidStatusException) {
            throw AdminException(e.status, e.message ?: "HTTP ${e.status}")
        }
        response.body
    }

    suspend fun element(method: HttpMethod, path: String, query: Map<String, Any?> = emptyMap(), body: Any? = null): JsonElement {
        val bytes = raw(method, path, query, body)
        return if (bytes.isEmpty()) JsonNull else JellyfinJson.parseToJsonElement(bytes.decodeToString())
    }

    suspend inline fun <reified T> get(path: String, query: Map<String, Any?> = emptyMap(), json: Json = JellyfinJson): T =
        decode(json.serializersModule.serializer(), raw(HttpMethod.GET, path, query), json)

    suspend inline fun <reified T> post(path: String, body: Any? = null, query: Map<String, Any?> = emptyMap(), json: Json = JellyfinJson): T =
        decode(json.serializersModule.serializer(), raw(HttpMethod.POST, path, query, encodeBody(body, json)), json)

    /** A POST whose answer doesn't matter (204, or a body nobody reads). */
    suspend fun send(path: String, body: Any? = null, query: Map<String, Any?> = emptyMap(), json: Json = JellyfinJson) {
        raw(HttpMethod.POST, path, query, encodeBody(body, json))
    }

    suspend fun delete(path: String, query: Map<String, Any?> = emptyMap()) {
        raw(HttpMethod.DELETE, path, query)
    }

    /** A settings object as Jellyfin sent it. */
    suspend fun getObject(path: String, query: Map<String, Any?> = emptyMap()): JsonObject =
        element(HttpMethod.GET, path, query) as? JsonObject ?: JsonObject(emptyMap())

    /**
     * Saves [patch] over the settings at [path] (GET, replace those keys, POST to [savePath], default [path]) and
     * returns what was saved. Reading first keeps changes made elsewhere since the page loaded.
     */
    suspend fun patchObject(path: String, patch: Map<String, JsonElement>, savePath: String = path, query: Map<String, Any?> = emptyMap()): JsonObject {
        val saved = JsonObject(getObject(path, query) + patch)
        raw(HttpMethod.POST, savePath, query, saved)
        return saved
    }

    fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray, json: Json): T {
        @Suppress("UNCHECKED_CAST")
        if (bytes.isEmpty()) return Unit as T
        return json.decodeFromString(serializer, bytes.decodeToString())
    }

    /** Models go as JSON this client writes (PascalCase for Jellyfin); JSON trees and text go as they are. */
    fun encodeBody(body: Any?, json: Json): Any? = when (body) {
        null, is JsonElement, is String -> body
        else -> encodeModel(body, json)
    }

    @Suppress("UNCHECKED_CAST")
    private fun encodeModel(body: Any, json: Json): JsonElement =
        json.encodeToJsonElement(json.serializersModule.serializer(body::class.java) as KSerializer<Any>, body)
}
