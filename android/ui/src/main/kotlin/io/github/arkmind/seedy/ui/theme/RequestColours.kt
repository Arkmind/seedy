package io.github.arkmind.seedy.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.arkmind.seedy.core.feature.RequestSeasonsViewModel
import io.github.arkmind.seedy.core.requests.RequestState

/** The colour a request's state is written in. */
@Composable
fun toneColour(tone: RequestState.Tone): Color = when (tone) {
    RequestState.Tone.Ok -> SeedyColors.Ok
    RequestState.Tone.Pending -> SeedyColors.Pending
    RequestState.Tone.Accent -> LocalAccent.current
    RequestState.Tone.Danger -> SeedyColors.Danger
    RequestState.Tone.Muted -> SeedyColors.Text3
}

/** The dot of a Seerr title's status pill ("ok", "pending", "danger", else muted). */
fun seerrToneColour(tone: String): Color = when (tone) {
    "ok" -> SeedyColors.Ok
    "pending" -> SeedyColors.Pending
    "danger" -> SeedyColors.Danger
    else -> SeedyColors.Text3
}

/** A season's pill in the request sheet: its background, its ink and its dot. */
fun seasonPillColours(kind: RequestSeasonsViewModel.Kind): Triple<Color, Color, Color> = when (kind) {
    RequestSeasonsViewModel.Kind.Server -> Triple(SeedyColors.Ok.copy(alpha = 0.1f), SeedyColors.Ok, SeedyColors.Ok)
    RequestSeasonsViewModel.Kind.Pending -> Triple(SeedyColors.Pending.copy(alpha = 0.1f), SeedyColors.Pending, SeedyColors.Pending)
    RequestSeasonsViewModel.Kind.Blocked -> Triple(Color(0xFF262626), SeedyColors.TextHero, SeedyColors.Danger)
    else -> Triple(Color(0xFF262626), SeedyColors.TextHero, Color(0xFF6A6A6A))
}
