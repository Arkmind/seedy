package io.github.arkmind.seedy.core.admin.media

import io.github.arkmind.seedy.core.i18n.formatNumber
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.settings.Choice

/*
 * The rows of the media pages' settings cards, ready to show: the pages build them from Jellyfin's settings, the phone
 * draws them with the settings kit (switch, value with a chevron, number, text) and hands changes back by [key].
 */

sealed interface Setting {
    val key: String
    val name: String
    val help: String?
}

data class SwitchSetting(override val key: String, override val name: String, override val help: String?, val on: Boolean, val enabled: Boolean = true) : Setting

/** A value picked from a list; [value] is what the row shows, [selected] the option's value. */
data class ChoiceSetting(
    override val key: String,
    override val name: String,
    override val help: String?,
    val value: String,
    val selected: String,
    val options: List<Choice<String>>,
) : Setting

/** A number typed in a sheet: [value] is what the row shows ("180 s"), [number] what the sheet starts with. */
data class NumberSetting(
    override val key: String,
    override val name: String,
    override val help: String?,
    val value: String,
    val number: Double,
    val min: Double,
    val max: Double,
    /** "s", "min", "Mbps"…: written after the field. */
    val unit: String? = null,
    val decimals: Boolean = false,
    /** The line under the sheet's title. */
    val text: String? = null,
) : Setting

/** Text typed in place (a field under the row's name). */
data class TextSetting(
    override val key: String,
    override val name: String,
    override val help: String?,
    val text: String,
    val placeholder: String = "",
    val mono: Boolean = false,
    val lines: Int = 1,
) : Setting

/** A row that opens a page or a sheet ([value] in grey before the chevron). */
data class LinkSetting(override val key: String, override val name: String, override val help: String?, val value: String) : Setting

data class SettingsGroup(val title: String?, val rows: List<Setting>, val foot: String? = null)

/** What a card of [Setting] rows does. */
interface SettingActions {
    fun setSwitch(key: String, on: Boolean) {}
    fun setChoice(key: String, value: String) {}
    fun setNumber(key: String, value: Double) {}
    fun setText(key: String, value: String) {}
    fun open(key: String) {}
}

/** "180", "0.5", "2,5" in French. */
fun numberText(v: Double): String = if (v % 1.0 == 0.0) formatNumber(v.toLong()) else formatNumber(v)

/** "180 s" (a unit after the number, as the boards write it). */
fun withUnit(v: Double, unit: String?): String = if (unit.isNullOrEmpty()) numberText(v) else "${numberText(v)} $unit"

/** Choices with the current value added when it isn't one of them. */
fun withCurrent(options: List<Choice<String>>, value: String, label: String = value): List<Choice<String>> =
    if (options.any { it.value == value }) options else options + Choice(value, label.ifEmpty { t("Not set") })

fun labelOf(options: List<Choice<String>>, value: String): String = options.firstOrNull { it.value == value }?.label ?: value
