package io.github.arkmind.seedy.core.format

import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.Lang
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/*
 * Dates as the boards write them (web/src/lib/format.ts): three-letter English months ("25 Sep", "Fri 23 Oct",
 * "25 Sep 2026") and the usual French abbreviations ("25 sept.", "ven. 23 oct.").
 */

private val MonthsEn = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val DaysEn = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val MonthsFr = listOf("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.")
private val DaysFr = listOf("lun.", "mar.", "mer.", "jeu.", "ven.", "sam.", "dim.")

private fun month(d: LocalDate) = (if (I18n.lang == Lang.Fr) MonthsFr else MonthsEn)[d.monthValue - 1]
private fun weekday(d: LocalDate) = (if (I18n.lang == Lang.Fr) DaysFr else DaysEn)[d.dayOfWeek.value - 1]

/** "25 Sep". */
fun dayMonth(d: LocalDate): String = "${d.dayOfMonth} ${month(d)}"

/** "25 Sep 2026". */
fun fullDate(d: LocalDate): String = "${d.dayOfMonth} ${month(d)} ${d.year}"

/** "Fri 23 Oct". */
fun weekdayDate(d: LocalDate): String = "${weekday(d)} ${dayMonth(d)}"

/** Seerr's "2026-10-23" (or a full timestamp) as a day; null for anything else. */
fun parseDay(s: String?): LocalDate? = s?.trim()?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.substring(0, 10)) }.getOrNull() }

/** Whole days from today to [d] (negative in the past). */
fun daysFromToday(d: LocalDate): Long = ChronoUnit.DAYS.between(LocalDate.now(), d)
