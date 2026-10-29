package io.github.arkmind.seedy.mobile.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arkmind.seedy.core.format.duration
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.player.audio.AudioEngine
import io.github.arkmind.seedy.player.audio.AudioState
import io.github.arkmind.seedy.player.audio.AudioTime
import io.github.arkmind.seedy.ui.components.AudioCover
import io.github.arkmind.seedy.ui.components.boxShadow
import io.github.arkmind.seedy.ui.components.SeekGlyph
import io.github.arkmind.seedy.ui.components.webId
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.IconPart
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import org.jellyfin.sdk.model.api.BaseItemDto

/** The app's audio player; null in screenshot tests and before sign-in. */
val LocalAudio = staticCompositionLocalOf<AudioEngine?> { null }

/** Whether the mini bar shows over the page bottom (something is loaded in the audio player). */
val LocalMiniBar = staticCompositionLocalOf { false }

/** What the floating mini bar covers above the tab bar (60 px bar + 8 px gap): lists add it to their bottom padding. */
val MiniBarSpace = 68.dp

/** [MiniBarSpace] while the mini bar shows, else nothing. */
@Composable
fun miniBarSpace(): Dp = if (LocalMiniBar.current) MiniBarSpace else 0.dp

/** Icons the audio players share (boards "Android · Music", "Android · Now playing"). */
object AudioIcons {
    val Previous = IconDef(2.2f, listOf(IconPart("M18 5v14L8 12z", filled = true), IconPart("M6 5v14")))
    val Next = IconDef(2.2f, listOf(IconPart("M6 5v14l10-7z", filled = true), IconPart("M18 5v14")))
    val Pause = IconDef(0f, listOf(IconPart("M7.2 4.5h1.6a1.2 1.2 0 0 1 1.2 1.2v12.6a1.2 1.2 0 0 1 -1.2 1.2h-1.6a1.2 1.2 0 0 1 -1.2 -1.2v-12.6a1.2 1.2 0 0 1 1.2 -1.2Z", filled = true), IconPart("M15.2 4.5h1.6a1.2 1.2 0 0 1 1.2 1.2v12.6a1.2 1.2 0 0 1 -1.2 1.2h-1.6a1.2 1.2 0 0 1 -1.2 -1.2v-12.6a1.2 1.2 0 0 1 1.2 -1.2Z", filled = true)))
    val Play = WebIcons.Play
}

/** A song's cover for the players: its own picture, its album's, or the album's drawn cover. */
@Composable
fun TrackCover(track: BaseItemDto?, modifier: Modifier = Modifier, width: Int = 300, title: String? = null, titleSize: androidx.compose.ui.unit.TextUnit = 17.sp) {
    val audio = LocalAudio.current
    AudioCover(track?.let { audio?.artwork(it, width) }, webId(track?.albumId ?: track?.id), modifier, title = title, titleSize = titleSize)
}

class MiniBarActions(
    val onOpen: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onToggle: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onBack30: () -> Unit = {},
)

/**
 * The mini player over the bottom of the pages (boards "Android · Music" and "Android · Books"): the cover, the song and
 * its artist, Previous, Play/Pause and Next; for an audiobook its chapter and time left, Back 30 seconds and Play/Pause.
 * A line along the bottom shows how far it is.
 */
@Composable
fun MiniBar(state: AudioState, time: AudioTime, actions: MiniBarActions, modifier: Modifier = Modifier) {
    val cur = state.current ?: return
    val book = state.book.takeIf { state.isBook }
    val title = book?.name ?: cur.track.name.orEmpty()
    val sub = if (book != null) {
        val chapter = book.chapters.lastOrNull { it.start <= time.position + 0.01 } ?: book.chapters.firstOrNull()
        listOfNotNull(
            chapter?.takeIf { book.chapters.size > 1 }?.let { t("Chapter {n}", "n" to it.index + 1) },
            t("{time} left", "time" to duration((book.duration - time.position).coerceAtLeast(0.0))),
        ).joinToString(" · ")
    } else {
        cur.track.artists?.joinToString(", ")?.ifEmpty { null } ?: cur.track.albumArtist.orEmpty()
    }
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.fillMaxWidth().padding(horizontal = 8.dp)
            .boxShadow(Color(0x8C000000), blur = 30.dp, y = 10.dp, radius = 16.dp)
            .clip(shape).background(Color(0xFF262626)),
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TrackCover(cur.track, Modifier.size(44.dp).clip(RoundedCornerShape(9.dp)), width = 120)
            Column(Modifier.weight(1f).tap(onClick = actions.onOpen), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                SText(title, size = 13.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
                SText(sub, size = 12.sp, color = SeedyColors.Text2, maxLines = 1)
            }
            if (book != null) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).tap(onClick = actions.onBack30), contentAlignment = Alignment.Center) {
                    SeekGlyph(back = true, seconds = 30, size = 24.dp, color = SeedyColors.White)
                }
            } else {
                BarButton(AudioIcons.Previous, t("Previous"), actions.onPrevious)
            }
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Inverse).tap(onClick = actions.onToggle),
                contentAlignment = Alignment.Center,
            ) {
                SIcon(if (state.playing) AudioIcons.Pause else AudioIcons.Play, size = 18.dp, color = SeedyColors.InkInverse)
            }
            if (book == null) BarButton(AudioIcons.Next, t("Next"), actions.onNext)
        }
        Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp).fillMaxWidth().height(2.dp).background(Color(0x26FFFFFF))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(time.fraction).background(SeedyColors.White))
        }
    }
}

@Composable
private fun BarButton(icon: IconDef, @Suppress("UNUSED_PARAMETER") label: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).tap(onClick = onClick), contentAlignment = Alignment.Center) {
        SIcon(icon, size = 20.dp, color = SeedyColors.White)
    }
}

/** The mini bar for the app's player: tapping it opens Now playing or the audiobook player. */
@Composable
fun AudioMiniBar(modifier: Modifier = Modifier) {
    val audio = LocalAudio.current ?: return
    val nav = LocalMobileNav.current
    val state by audio.state.collectAsStateWithLifecycle()
    val time by audio.time.collectAsStateWithLifecycle()
    MiniBar(
        state, time,
        MiniBarActions(
            onOpen = { nav.go(if (state.isBook) Route.AudiobookPlayer else Route.NowPlaying) },
            onPrevious = audio::previous, onToggle = audio::togglePlay, onNext = audio::next, onBack30 = { audio.skip(-30.0) },
        ),
        modifier,
    )
}

/**
 * Provides [LocalMiniBar] to [content] (true while something is loaded in the player) and shows the player's messages
 * ("Couldn't play …") as toasts.
 */
@Composable
fun AudioScope(content: @Composable () -> Unit) {
    val audio = LocalAudio.current
    val shown = audio?.state?.collectAsStateWithLifecycle()?.value?.current != null
    var message by remember { mutableStateOf<String?>(null) }
    if (audio != null) LaunchedEffect(audio) { audio.messages.collect { message = it } }
    CompositionLocalProvider(LocalMiniBar provides shown) {
        Box {
            content()
            Toast(message, { message = null }, Modifier.align(Alignment.BottomCenter).padding(bottom = 160.dp))
        }
    }
}
