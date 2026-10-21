package io.github.arkmind.seedy.core.admin.people

import io.github.arkmind.seedy.core.admin.AdminDevice
import io.github.arkmind.seedy.core.admin.AdminException
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.ItemsResult
import io.github.arkmind.seedy.core.admin.normId
import io.github.arkmind.seedy.core.admin.sameId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/* The people pages' writes, as web/src/screens/server/api.ts makes them. */

/** Jellyfin replaces the whole policy, so changes go onto the policy as the server has it now. */
suspend fun patchUserPolicy(http: AdminHttp, userId: String, patch: Map<String, JsonElement>): JsonObject {
    val fresh = http.getObject("/Users/$userId")
    val policy = fresh["Policy"] as? JsonObject ?: JsonObject(emptyMap())
    val next = JsonObject(policy + patch)
    http.send("/Users/$userId/Policy", next)
    return next
}

/** Renaming posts the whole user, so it's read fresh: settings changed since the page loaded aren't put back. */
suspend fun renameUser(http: AdminHttp, userId: String, name: String) {
    val fresh = http.getObject("/Users/$userId")
    http.send("/Users", JsonObject(fresh + ("Name" to JsonPrimitive(name))), mapOf("userId" to userId))
}

suspend fun setUserPassword(http: AdminHttp, userId: String, new: String, current: String?) {
    http.send("/Users/Password", buildJsonObject { put("NewPw", new); put("CurrentPw", current.orEmpty()); put("ResetPassword", false) }, mapOf("userId" to userId))
}

suspend fun resetUserPassword(http: AdminHttp, userId: String) {
    http.send("/Users/Password", buildJsonObject { put("ResetPassword", true) }, mapOf("userId" to userId))
}

/**
 * Signs a person out on every device: removing a device ends its session and revokes its token. This device stays
 * when it's theirs. Returns how many were signed out.
 */
suspend fun signOutEverywhere(http: AdminHttp, userId: String, me: String, ownDevice: String): Int {
    val list = http.get<ItemsResult<AdminDevice>>("/Devices", mapOf("userId" to userId)).items
        .filter { normId(it.lastUserId) == normId(userId) && !(sameId(me, userId) && it.id == ownDevice) }
        .distinctBy { it.id }
    var n = 0
    for (d in list) {
        try {
            http.delete("/Devices", mapOf("id" to d.id))
            n++
        } catch (_: AdminException) {
            // Already gone.
        }
    }
    return n
}

/**
 * Removes devices. Jellyfin refuses a device that has gone since the list loaded (400); those are skipped.
 * Returns how many went.
 */
suspend fun deleteDevices(http: AdminHttp, ids: List<String>): Int {
    var n = 0
    for (id in ids) {
        try {
            http.delete("/Devices", mapOf("id" to id))
            n++
        } catch (e: AdminException) {
            if (e.status != 400 && e.status != 404) throw e
        }
    }
    return n
}
