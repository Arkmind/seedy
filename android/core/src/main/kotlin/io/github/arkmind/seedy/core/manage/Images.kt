package io.github.arkmind.seedy.core.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.errorText
import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Locale

/*
 * Edit images (board ItemImages): the title's slots (poster, backdrops, logo, thumbnail, banner) with what's in them,
 * and for the picked slot what the providers offer (GET /Items/{id}/RemoteImages). Picking one downloads it into the
 * slot, a slot's bin removes its image, and Upload sends one from the phone. Every change saves right away.
 */

/** An image in a slot. */
data class SlotImage(val index: Int?, val url: String, val width: Int?, val height: Int?)

data class ImageSlot(val type: String, val name: String, val sub: String, val images: List<SlotImage>, val many: Boolean)

data class OfferedImage(val url: String, val thumb: String, val meta: String, val inUse: Boolean)

data class ImagesState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val name: String = "",
    val slots: List<ImageSlot> = emptyList(),
    val picked: String = "Primary",
    /** Null while loading. */
    val offered: List<OfferedImage>? = null,
    val offeredTotal: Int = 0,
    val providers: List<String> = emptyList(),
    val provider: String? = null,
    val allLanguages: Boolean = true,
    /** The image being added or the slot being emptied. */
    val busy: String? = null,
    val message: String? = null,
) {
    val slot: ImageSlot? get() = slots.firstOrNull { it.type == picked }
}

interface ImagesActions {
    fun pick(type: String) {}
    fun provider(name: String?) {}
    fun languages(all: Boolean) {}
    fun use(image: OfferedImage) {}
    fun remove(type: String, index: Int?) {}
    fun upload(bytes: ByteArray, mime: String) {}
    fun retry() {}
    fun messageShown() {}
}

class ImagesViewModel(session: UserSession, private val id: String) : ViewModel(), ImagesActions {
    private val api = ManageApi(session)
    private val base = session.api.baseUrl.orEmpty().trimEnd('/')
    private val _state = MutableStateFlow(ImagesState())
    val state: StateFlow<ImagesState> = _state.asStateFlow()
    private var offerJob: Job? = null
    /** Provider images added here (Jellyfin doesn't say where an image came from). */
    private val added = HashSet<String>()
    private var itemType = ""

    init {
        load()
    }

    private fun load() {
        _state.update { it.copy(loading = true, failed = false) }
        viewModelScope.launch {
            val result = runCatching {
                val item = api.item(id)
                itemType = (item["Type"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                (item["Name"] as? JsonPrimitive)?.contentOrNull.orEmpty() to slots(itemType, api.images(id))
            }
            result.onSuccess { (name, slots) ->
                _state.update { it.copy(loading = false, name = name, slots = slots) }
                offers()
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(loading = false, failed = true, message = t("Couldn’t load the images. {error}", "error" to errorText(e))) }
            }
        }
    }

    private suspend fun reloadSlots() {
        runCatching { slots(itemType, api.images(id)) }.onSuccess { s -> _state.update { it.copy(slots = s) } }
    }

    private fun slots(itemType: String, list: List<JsonObject>): List<ImageSlot> {
        fun of(type: String) = list.filter { (it["ImageType"] as? JsonPrimitive)?.contentOrNull == type }.map { o ->
            val index = (o["ImageIndex"] as? JsonPrimitive)?.intOrNull
            val tag = (o["ImageTag"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            SlotImage(
                index,
                "$base/Items/$id/Images/$type" + (index?.let { "/$it" } ?: "") + "?tag=$tag&maxWidth=480&quality=90",
                (o["Width"] as? JsonPrimitive)?.intOrNull, (o["Height"] as? JsonPrimitive)?.intOrNull,
            )
        }
        fun size(i: SlotImage?) = if (i?.width != null && i.height != null) "${i.width} × ${i.height}" else null
        val poster = of("Primary")
        val backdrops = of("Backdrop")
        val logo = of("Logo")
        val thumb = of("Thumb")
        val banner = of("Banner")
        val none = t("None yet")
        return listOf(
            ImageSlot("Primary", if (itemType == "MusicAlbum") t("Cover") else t("Poster"), size(poster.firstOrNull()) ?: none, poster, false),
            ImageSlot("Backdrop", if (backdrops.isEmpty()) t("Backdrops") else t("Backdrops · {n}", "n" to backdrops.size), t("Behind the title"), backdrops, true),
            ImageSlot("Logo", t("Logo"), if (logo.isEmpty()) none else t("Title art"), logo, false),
            ImageSlot("Thumb", t("Thumbnail"), listOfNotNull(t("16:9 cards"), size(thumb.firstOrNull())).joinToString(" · "), thumb, false),
            ImageSlot("Banner", t("Banner"), if (banner.isEmpty()) t("None yet · some TV apps use it") else size(banner.first()).orEmpty(), banner, false),
        )
    }

    private fun offers() {
        val s = _state.value
        offerJob?.cancel()
        _state.update { it.copy(offered = null) }
        offerJob = viewModelScope.launch {
            val result = runCatching { api.remoteImages(id, s.picked, s.provider, s.allLanguages) }
            result.onSuccess { o ->
                val images = (o["Images"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { img ->
                    fun text(k: String) = (img[k] as? JsonPrimitive)?.contentOrNull
                    val w = (img["Width"] as? JsonPrimitive)?.intOrNull
                    val h = (img["Height"] as? JsonPrimitive)?.intOrNull
                    val url = text("Url").orEmpty()
                    OfferedImage(
                        url, text("ThumbnailUrl")?.takeIf { it.isNotBlank() } ?: url,
                        listOfNotNull(if (w != null && h != null) "$w × $h" else null, text("ProviderName")?.let(::providerName), language(text("Language"))).joinToString(" · "),
                        url in added,
                    )
                }.filter { it.url.isNotEmpty() }
                val providers = (o["Providers"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                _state.update { it.copy(offered = images, offeredTotal = images.size, providers = providers.ifEmpty { it.providers }) }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(offered = emptyList(), message = t("Couldn’t ask the providers. {error}", "error" to errorText(e))) }
            }
        }
    }

    override fun pick(type: String) {
        if (type == _state.value.picked) return
        _state.update { it.copy(picked = type, provider = null) }
        offers()
    }

    override fun provider(name: String?) {
        _state.update { it.copy(provider = name) }
        offers()
    }

    override fun languages(all: Boolean) {
        _state.update { it.copy(allLanguages = all) }
        offers()
    }

    override fun retry() = load()

    override fun messageShown() = _state.update { it.copy(message = null) }

    override fun use(image: OfferedImage) {
        val type = _state.value.picked
        _state.update { it.copy(busy = image.url) }
        viewModelScope.launch {
            val result = runCatching { api.downloadImage(id, type, image.url) }
            result.onSuccess {
                added += image.url
                reloadSlots()
                _state.update { s -> s.copy(busy = null, offered = s.offered?.map { if (it.url == image.url) it.copy(inUse = true) else it }, message = saved(type)) }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(busy = null, message = t("Couldn’t add it. {error}", "error" to errorText(e))) }
            }
        }
    }

    override fun remove(type: String, index: Int?) {
        _state.update { it.copy(busy = "$type:$index") }
        viewModelScope.launch {
            val result = runCatching { api.deleteImage(id, type, index) }
            result.onSuccess {
                reloadSlots()
                _state.update { it.copy(busy = null, message = t("Removed")) }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(busy = null, message = t("Couldn’t remove it. {error}", "error" to errorText(e))) }
            }
        }
    }

    override fun upload(bytes: ByteArray, mime: String) {
        val type = _state.value.picked
        _state.update { it.copy(busy = "upload") }
        viewModelScope.launch {
            val result = runCatching { api.uploadImage(id, type, bytes, mime) }
            result.onSuccess {
                reloadSlots()
                _state.update { it.copy(busy = null, message = saved(type)) }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(busy = null, message = t("Couldn’t upload it. {error}", "error" to errorText(e))) }
            }
        }
    }

    private fun saved(type: String): String = when (type) {
        "Backdrop" -> t("Backdrop added")
        "Primary" -> t("Poster changed")
        "Logo" -> t("Logo changed")
        "Thumb" -> t("Thumbnail changed")
        "Banner" -> t("Banner changed")
        else -> t("Saved")
    }

    companion object {
        fun providerName(name: String): String = when (name) {
            "TheMovieDb" -> "TMDb"
            "TheTVDB", "TheTvdb" -> "TVDb"
            "FanArt", "Fanart" -> "Fanart"
            else -> name
        }

        /** "English", or "no text" when the image has no language. */
        fun language(code: String?): String =
            if (code.isNullOrBlank()) t("no text") else Locale.forLanguageTag(code).getDisplayLanguage(I18n.locale).replaceFirstChar { it.titlecase(I18n.locale) }.ifEmpty { code }
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
