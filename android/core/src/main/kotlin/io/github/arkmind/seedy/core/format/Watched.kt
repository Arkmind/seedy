package io.github.arkmind.seedy.core.format

import io.github.arkmind.seedy.core.i18n.t
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.LocalDate

/*
 * The watched chips of the details pages (web: watchedChip / seriesChip in screens/browse/details-parts.tsx, boards
 * "Movie details · watched" and "Series details · up to date").
 */

/** A watched title's chip: "Watched 2 Nov" (with the year when it isn't this one), "Watched" without a date. */
fun watchedChip(item: BaseItemDto, today: LocalDate = LocalDate.now()): String? {
    val data = item.userData ?: return null
    if (data.played != true) return null
    val day = data.lastPlayedDate?.toLocalDate() ?: return t("Watched")
    return t("Watched {date}", "date" to if (day.year == today.year) dayMonth(day) else fullDate(day))
}

/**
 * A series' chip: "9 of 18 watched", then "Up to date" (or "Watched" once the show has ended); nothing before the
 * first episode. Jellyfin counts both numbers without placeholder episodes, specials included, as its Played flag does.
 */
fun seriesChip(item: BaseItemDto): String? {
    val data = item.userData
    if (data?.played == true) return if (item.status == "Ended") t("Watched") else t("Up to date")
    val total = item.recursiveItemCount ?: 0
    val watched = total - (data?.unplayedItemCount ?: total)
    return if (watched in 1 until total) t("{watched} of {total} watched", "watched" to watched, "total" to total) else null
}

/** The chip for whichever title a details page shows. */
fun detailsChip(item: BaseItemDto, today: LocalDate = LocalDate.now()): String? =
    if (item.type == BaseItemKind.SERIES) seriesChip(item) else watchedChip(item, today)

/**
 * A season's chip from its episodes: "1 of 10" while watching, "All 4 aired" once caught up on a season still airing,
 * "All 10 watched" on a finished one; nothing before its first episode.
 */
fun seasonChip(episodes: List<BaseItemDto>, today: LocalDate = LocalDate.now()): String? {
    val available = episodes.filter { !it.isVirtual }
    val watched = episodes.count { it.userData?.played == true }
    val all = available.isNotEmpty() && available.all { it.userData?.played == true }
    val airing = episodes.any { it.isVirtual && (it.premiereDay?.isAfter(today) ?: true) }
    return when {
        all && airing -> t("All {n} aired", "n" to available.size)
        all -> t("All {n} watched", "n" to available.size)
        watched > 0 -> t("{watched} of {total}", "watched" to watched, "total" to episodes.size)
        else -> null
    }
}

/** Up to date on a show: "Next episode, False Summit, arrives Fri 23 Oct". */
fun nextToAirLine(ep: BaseItemDto): String? {
    val day = ep.premiereDay ?: return null
    val name = ep.name?.takeIf { it.isNotBlank() }
    return if (name != null) t("Next episode, {name}, arrives {date}", "name" to name, "date" to weekdayDate(day))
    else t("Next episode arrives {date}", "date" to weekdayDate(day))
}
