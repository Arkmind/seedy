package io.github.arkmind.seedy.core.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.AdminDevice
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.ItemsResult
import io.github.arkmind.seedy.core.admin.people.DeviceRow
import io.github.arkmind.seedy.core.admin.people.appLabel
import io.github.arkmind.seedy.core.admin.people.deleteDevices
import io.github.arkmind.seedy.core.admin.people.deviceName
import io.github.arkmind.seedy.core.admin.people.kindOf
import io.github.arkmind.seedy.core.admin.people.lastActive
import io.github.arkmind.seedy.core.admin.realDate
import io.github.arkmind.seedy.core.admin.sameId
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock

/** The apps signed in to this profile; null while loading. */
data class MyDevicesState(
    val devices: List<DeviceRow>? = null,
    val error: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

interface MyDevicesActions {
    fun reload() {}
    fun signOut(id: String) {}
    fun signOutOthers() {}
    fun messageShown() {}
}

/** The devices Jellyfin lists with this profile as their last user (only admins may list and remove devices). */
suspend fun myDevices(http: AdminHttp, userId: String): List<AdminDevice> =
    http.get<ItemsResult<AdminDevice>>("/Devices").items
        .filter { sameId(it.lastUserId, userId) }
        .distinctBy { it.id }
        .sortedByDescending { it.dateLastActivity.orEmpty() }

/**
 * Settings › Profile › Signed in on: where this profile is signed in, newest first, and signing one out (Jellyfin ends
 * its session and revokes its token). This phone stays: Sign out in Settings does that.
 */
class MyDevicesViewModel(private val session: UserSession, private val clock: Clock = Clock.systemDefaultZone()) : ViewModel(), MyDevicesActions {
    private val http = AdminHttp(session.api)
    private val mine = session.api.deviceInfo.id
    private val me = session.userId.toString()
    private val _state = MutableStateFlow(MyDevicesState())
    val state: StateFlow<MyDevicesState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            runCatching { myDevices(http, me) }
                .onSuccess { list -> _state.update { it.copy(devices = list.map(::row), error = null) } }
                .onFailure { e -> if (e is CancellationException) throw e; _state.update { it.copy(error = t("Couldn’t load your devices.")) } }
        }
    }

    private fun row(d: AdminDevice) = DeviceRow(
        id = d.id, name = deviceName(d), reported = d.name.orEmpty(), kind = kindOf(d), app = appLabel(d),
        userId = d.lastUserId, user = d.lastUserName.orEmpty(), userIndex = 0, userImage = null,
        last = lastActive(realDate(d.dateLastActivity), clock), mine = d.id == mine,
    )

    override fun reload() = load()

    override fun signOut(id: String) {
        val row = _state.value.devices?.firstOrNull { it.id == id } ?: return
        if (row.mine) return
        viewModelScope.launch {
            runCatching { deleteDevices(http, listOf(id)) }
                .onSuccess { _state.update { s -> s.copy(devices = s.devices?.filter { it.id != id }, message = t("{name} signed out", "name" to row.name)) } }
                .onFailure { if (it is CancellationException) throw it; _state.update { s -> s.copy(message = t("Couldn’t sign that device out.")) } }
        }
    }

    override fun signOutOthers() {
        val others = _state.value.devices.orEmpty().filter { !it.mine }
        if (others.isEmpty()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { deleteDevices(http, others.map { it.id }) }
                .onSuccess { n -> _state.update { s -> s.copy(message = tn(n, "{n} device signed out", "{n} devices signed out")) } }
                .onFailure { if (it is CancellationException) throw it; _state.update { s -> s.copy(message = t("Couldn’t sign the other devices out.")) } }
            _state.update { it.copy(busy = false) }
            load()
        }
    }

    override fun messageShown() = _state.update { it.copy(message = null) }
}
