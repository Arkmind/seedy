package io.github.arkmind.seedy.mobile.music

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.music.Mix
import io.github.arkmind.seedy.core.music.artistsOf
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.parts.HeaderButton
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.player.audio.AudioState
import io.github.arkmind.seedy.player.audio.SourceKind
import io.github.arkmind.seedy.ui.components.AudioCover
import io.github.arkmind.seedy.ui.components.CoverPalettes
import io.github.arkmind.seedy.ui.components.boxShadow
import io.github.arkmind.seedy.ui.components.coverUrl
import io.github.arkmind.seedy.ui.components.initialsOf
import io.github.arkmind.seedy.ui.components.webId
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.IconPart
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

/* Pieces the phone's music pages share (boards "Android · Music", "· Album", "· Artist", "· Now playing"). */

/** The music boards' icons, path for path (web/src/audio/icons.tsx). */
object MusicIcons {
    val Back = IconDef(2.2f, "M15 5l-7 7 7 7")
    val Down = IconDef(2.2f, "M6 9l6 6 6-6")
    val Search = IconDef(2.2f, "M4 11a7 7 0 1 0 14 0a7 7 0 1 0 -14 0Z", "M20 20l-4-4")
    val Cast = IconDef(2.2f, "M3 8V6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6", "M3 12a8 8 0 0 1 8 8", "M3 16a4 4 0 0 1 4 4", "M3 20h.01")
    val More = IconDef(
        0f,
        listOf(
            IconPart("M3.8 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0Z", filled = true),
            IconPart("M10.3 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0Z", filled = true),
            IconPart("M16.8 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0Z", filled = true),
        ),
    )
    val Heart = IconDef(2.2f, "M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z")
    val HeartOn = IconDef(2.2f, listOf(IconPart("M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z", filled = true)))
    val Download = IconDef(2.2f, "M12 3v12", "M7 10l5 5 5-5", "M5 20h14")
    val Queue = IconDef(2.2f, listOf(IconPart("M4 6h12"), IconPart("M4 11h12"), IconPart("M4 16h7"), IconPart("M16 15v6l5-3z", filled = true)))
    val QueueNext = IconDef(2.2f, "M4 6h12", "M4 11h12", "M4 16h7", "M18 13v8", "M14 17l4 4 4-4")
    val PlaylistAdd = IconDef(2.2f, "M4 6h12", "M4 11h12", "M4 16h7", "M18 14v7", "M14.5 17.5h7")
    val Shuffle = IconDef(2.2f, "M16 3h5v5", "M4 20L21 3", "M21 16v5h-5", "M15 15l6 6", "M4 4l5 5")
    val Repeat = IconDef(2.2f, "M17 2l3 3-3 3", "M4 11V9a4 4 0 0 1 4-4h12", "M7 22l-3-3 3-3", "M20 13v2a4 4 0 0 1-4 4H4")
    val Play = IconDef(0f, listOf(IconPart("M7 4.5v15l12-7.5z", filled = true)))
    val Previous = IconDef(2.2f, listOf(IconPart("M18 5v14L8 12z", filled = true), IconPart("M6 5v14")))
    val Next = IconDef(2.2f, listOf(IconPart("M6 5v14l10-7z", filled = true), IconPart("M18 5v14")))
    val Pause = IconDef(
        0f,
        listOf(
            IconPart("M7.2 4.5h1.6a1.2 1.2 0 0 1 1.2 1.2v12.6a1.2 1.2 0 0 1 -1.2 1.2h-1.6a1.2 1.2 0 0 1 -1.2 -1.2v-12.6a1.2 1.2 0 0 1 1.2 -1.2Z", filled = true),
            IconPart("M15.2 4.5h1.6a1.2 1.2 0 0 1 1.2 1.2v12.6a1.2 1.2 0 0 1 -1.2 1.2h-1.6a1.2 1.2 0 0 1 -1.2 -1.2v-12.6a1.2 1.2 0 0 1 1.2 -1.2Z", filled = true),
        ),
    )
    val Radio = IconDef(2.2f, "M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z", "M8.5 8.5a5 5 0 0 0 0 7", "M15.5 8.5a5 5 0 0 1 0 7", "M5.6 5.6a9 9 0 0 0 0 12.8", "M18.4 5.6a9 9 0 0 1 0 12.8")
    val Mix = IconDef(2.2f, "M3 12h3l3-7 4 14 3-7h5")
    val Lyrics = IconDef(2.2f, "M4 6h10", "M4 11h7", "M4 16h5", "M14 16a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z", "M20 16V6l-3 1")
    val Album = IconDef(2.2f, "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0Z", "M9.5 12a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0Z")
    val Artist = IconDef(2.2f, "M8 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0Z", "M4 21a8 8 0 0 1 16 0")
    val Info = IconDef(2.2f, "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0Z", "M12 11v5", "M12 8h.01")
    val Close = IconDef(2.4f, "M6 6l12 12", "M18 6L6 18")
    val Handle = IconDef(2.2f, "M5 9h14", "M5 15h14")
    val Trash = IconDef(2.2f, "M4 7h16", "M10 11v6", "M14 11v6", "M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12", "M9 7V4h6v3")
    val Sort = IconDef(2f, "M7 4v16", "M3 16l4 4 4-4", "M12 6h9", "M12 11h6", "M12 16h3")
    val Check = IconDef(2.2f, "M5 12.5l4.5 4.5L19 7.5")
    val Plus = IconDef(2.4f, "M12 5v14", "M5 12h14")
}

/** What the player is on, for the pages' lit rows and tiles. */
data class Playing(
    val trackId: UUID? = null,
    /** The queue's source (an album, playlist or artist id, 32 characters as the web keeps them, or dashed). */
    val sourceId: String? = null,
    val sourceKind: SourceKind? = null,
    val albumId: UUID? = null,
    val playing: Boolean = false,
) {
    fun isTrack(id: UUID?) = id != null && id == trackId

    /** Whether the queue plays from [id] (an album, playlist or artist). */
    fun isSource(id: UUID?): Boolean = id != null && trackId != null && sameId(sourceId, id)

    companion object {
        fun of(s: AudioState): Playing = Playing(
            s.current?.track?.id, s.source?.id, s.source?.kind, s.current?.track?.albumId, s.playing,
        )

        fun sameId(a: String?, b: UUID): Boolean = a != null && a.replace("-", "").equals(b.toString().replace("-", ""), ignoreCase = true)
    }
}

/* ── Covers ── */

/** An album's (or any item's) cover: its picture, its album's, or the drawn one with [title] set on it. */
@Composable
fun ItemCover(item: BaseItemDto?, modifier: Modifier = Modifier, width: Int = 360, title: String? = null, titleSize: TextUnit = 12.sp) {
    val images = LocalViewer.current.images
    AudioCover(coverUrl(item, images, width), webId(item?.albumId ?: item?.id), modifier, title = title, titleSize = titleSize)
}

/** An artist's picture, or their initials on the drawn colours. */
@Composable
fun ArtistCover(artist: BaseItemDto, modifier: Modifier = Modifier, width: Int = 360, initialsSize: TextUnit = 32.sp) {
    val images = LocalViewer.current.images
    AudioCover(coverUrl(artist, images, width), webId(artist.id), modifier, initials = initialsOf(artist.name), initialsSize = initialsSize)
}

/** Four covers in a square (a playlist without its own picture); fewer covers repeat. */
@Composable
fun MosaicCover(covers: List<BaseItemDto>, modifier: Modifier = Modifier, width: Int = 180) {
    val four = if (covers.isEmpty()) emptyList() else List(4) { covers[it % covers.size] }
    Column(modifier) {
        for (r in 0..1) Row(Modifier.weight(1f).fillMaxWidth()) {
            for (c in 0..1) ItemCover(four.getOrNull(r * 2 + c), Modifier.weight(1f).fillMaxSize(), width)
        }
    }
}

/** A playlist's cover: its picture, else its songs' covers in a square. */
@Composable
fun PlaylistCover(playlist: BaseItemDto, covers: List<BaseItemDto>, modifier: Modifier = Modifier, titleSize: TextUnit = 12.sp) {
    if (covers.isNotEmpty() && playlist.imageTags.isNullOrEmpty()) MosaicCover(covers, modifier)
    else ItemCover(playlist, modifier, title = playlist.name, titleSize = titleSize)
}

/* ── Small parts ── */

/**
 * The "Playing" bars in the accent; they move while the song plays ([animate] off in screenshot tests: the bars then
 * stand as the boards draw them).
 */
@Composable
fun EqBars(playing: Boolean, modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = LocalAccent.current) {
    val heights = if (playing && LocalMusicMotion.current) {
        val tr = rememberInfiniteTransition(label = "eq")
        listOf(0, 1, 2).map { i ->
            tr.animateFloat(
                0f, 1f, infiniteRepeatable(tween(listOf(900, 700, 1100)[i], easing = LinearEasing), RepeatMode.Reverse), label = "eq$i",
            ).value
        }
    } else null
    Canvas(modifier.size(size)) {
        val k = this.size.width / 24f
        val full = listOf(11f, 16f, 8f)
        val xs = listOf(4f, 10.25f, 16.5f)
        for (i in 0..2) {
            val h = heights?.let { (4f + (16f - 4f) * (if (i == 1) 1f - it[i] else it[i])).coerceIn(4f, 16f) } ?: full[i]
            drawRoundRect(color, topLeft = Offset(xs[i] * k, (20f - h) * k), size = Size(3.5f * k, h * k), cornerRadius = CornerRadius(1f * k))
        }
    }
}

/** Whether the eq bars move (off in screenshot tests). */
val LocalMusicMotion = androidx.compose.runtime.staticCompositionLocalOf { true }

/** A music page's 36 px chip (board "Android · Music": 12 px corners). */
@Composable
fun MusicChip(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.height(36.dp).clip(RoundedCornerShape(12.dp)).background(if (on) SeedyColors.Inverse else SeedyColors.Surface)
            .tap(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        SText(label, size = 13.sp, weight = if (on) 800 else 600, color = if (on) SeedyColors.InkInverse else SeedyColors.Text, maxLines = 1)
    }
}

/** A section's title and its link ("See all"), 22 px above and 10 under. */
@Composable
fun SectionHead(title: String, modifier: Modifier = Modifier, link: String? = null, onLink: () -> Unit = {}, top: Dp = 22.dp, bottom: Dp = 10.dp) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = top, bottom = bottom),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SText(title, Modifier.weight(1f).alignByBaseline(), size = 17.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
        if (link != null) SText(link, Modifier.alignByBaseline().tap(onClick = onLink), size = 13.sp, weight = 600, color = SeedyColors.Text2, maxLines = 1)
    }
}

/** The music pages' header: Back, a title (22 px black), then [actions] (44 px buttons). */
@Composable
fun MusicHeader(title: String?, onBack: () -> Unit, modifier: Modifier = Modifier, top: Dp, actions: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = top),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HeaderButton(MusicIcons.Back, onBack)
        if (title != null) SText(title, Modifier.weight(1f).padding(start = 6.dp), size = 22.sp, weight = 900, color = SeedyColors.White, maxLines = 1)
        else Spacer(Modifier.weight(1f))
        actions()
    }
}

/** A 44 px icon button (cast at 21 px, the rest at 22). */
@Composable
fun IconButton44(icon: IconDef, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = SeedyColors.White, box: Dp = 44.dp) {
    Box(modifier.size(box).clip(RoundedCornerShape(13.dp)).tap(onClick = onClick), contentAlignment = Alignment.Center) {
        SIcon(icon, size = size, color = color)
    }
}

/** A row of cards that scrolls sideways, 20 px in, 12 px apart. */
@Composable
fun CardRow(modifier: Modifier = Modifier, gap: Dp = 12.dp, content: LazyListScope.() -> Unit) {
    LazyRow(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(gap), content = content)
}

/* ── Cards ── */

/** An album card: the cover (its title set on a drawn one), the title and a line under it. */
@Composable
fun AlbumCard(album: BaseItemDto, sub: String, onClick: () -> Unit, modifier: Modifier = Modifier, coverTitle: TextUnit = 12.sp) {
    Column(modifier.tap(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ItemCover(album, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(11.dp)), title = album.name, titleSize = coverTitle)
        CardText(album.name.orEmpty(), sub)
    }
}

@Composable
private fun CardText(title: String, sub: String, center: Boolean = false) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp), horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) {
        SText(title, size = 13.sp, weight = 700, color = SeedyColors.White, maxLines = 1, align = if (center) TextAlign.Center else null)
        if (sub.isNotEmpty()) SText(sub, size = 12.sp, color = SeedyColors.Text3, maxLines = 1, align = if (center) TextAlign.Center else null)
    }
}

/** An artist: their picture in a circle, the name and a line under it, centred. */
@Composable
fun ArtistCard(artist: BaseItemDto, sub: String, onClick: () -> Unit, modifier: Modifier = Modifier, initialsSize: TextUnit = 30.sp) {
    Column(modifier.tap(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ArtistCover(artist, Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape), initialsSize = initialsSize)
        CardText(artist.name.orEmpty(), sub, center = true)
    }
}

/** A playlist card: its picture or four of its songs' covers, the name and "48 songs · 3 h 12 min". */
@Composable
fun PlaylistCard(playlist: BaseItemDto, covers: List<BaseItemDto>, sub: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.tap(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PlaylistCover(playlist, covers, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(11.dp)))
        CardText(playlist.name.orEmpty(), sub)
    }
}

/** New playlist: a ringed square with a plus, the card's size. */
@Composable
fun NewPlaylistTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.tap(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(11.dp))
                .drawBehind { drawRoundRect(SeedyColors.Surface2, cornerRadius = CornerRadius(11.dp.toPx()), style = Stroke(2.dp.toPx())) },
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(SeedyColors.Surface), contentAlignment = Alignment.Center) {
                SIcon(MusicIcons.Plus, size = 20.dp, color = SeedyColors.White)
            }
            SText(t("New playlist"), size = 13.sp, weight = 700, color = SeedyColors.TextHero, maxLines = 1)
        }
    }
}

/** Jump back in: the cover, the title (two lines), and the bars when it's what plays. */
@Composable
fun JumpTile(item: BaseItemDto, current: Boolean, playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(11.dp)).background(if (current) Color(0xFF262626) else SeedyColors.Row)
            .tap(onClick = onClick).padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (item.type == org.jellyfin.sdk.model.api.BaseItemKind.MUSIC_ARTIST) ArtistCover(item, Modifier.size(56.dp), width = 144, initialsSize = 18.sp)
        else ItemCover(item, Modifier.size(56.dp), width = 144)
        SText(item.name.orEmpty(), Modifier.weight(1f), size = 12.sp, weight = 700, lineHeight = 1.3f, color = SeedyColors.White, maxLines = 2)
        if (current) EqBars(playing)
    }
}

/** A mix card (Made for you): two covers at the top right, the title and the line under it, in the first cover's colours. */
@Composable
fun MixCard(mix: Mix, palette: List<Color>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.size(236.dp, 124.dp).clip(shape)
            .cssGradient(135f, listOf(palette[1], palette[0])).tap(onClick = onClick),
    ) {
        Row(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp)) {
            mix.covers.take(2).forEachIndexed { i, c ->
                Box(
                    Modifier.offset(x = if (i == 1) (-18).dp else 0.dp).size(52.dp)
                        .boxShadow(Color(0x66000000), blur = 16.dp, y = 6.dp, radius = 9.dp).clip(RoundedCornerShape(9.dp)),
                ) { ItemCover(c, Modifier.fillMaxSize(), width = 240) }
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(mix.title, size = 16.sp, weight = 900, color = SeedyColors.White, maxLines = 1)
            SText(mix.sub, size = 12.sp, color = Color(0xBFFFFFFF), maxLines = 1)
        }
    }
}

/** The genre tiles' colours, by tile (the web's GENRE_ORDER over the cover palettes' second colour). */
private val GenreOrder = listOf(0, 8, 2, 6, 4, 3, 1, 7, 5)

/** A genre: its colour, the name, its albums and a sample cover tilted into the corner. */
@Composable
fun GenreTile(name: String, count: String, index: Int, sample: BaseItemDto?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg = CoverPalettes[GenreOrder[index % GenreOrder.size] % CoverPalettes.size][1]
    Box(modifier.height(96.dp).clip(RoundedCornerShape(16.dp)).background(bg).tap(onClick = onClick)) {
        if (sample != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = 12.dp, y = 12.dp).size(68.dp).rotate(22f)
                    .boxShadow(Color(0x66000000), blur = 24.dp, y = 10.dp, radius = 10.dp).clip(RoundedCornerShape(10.dp)),
            ) { ItemCover(sample, Modifier.fillMaxSize(), width = 180) }
        }
        SText(name, Modifier.padding(start = 14.dp, top = 12.dp, end = 44.dp), size = 16.sp, weight = 900, color = SeedyColors.White, maxLines = 2)
        if (count.isNotEmpty()) SText(count, Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 11.dp), size = 12.sp, weight = 600, color = Color(0xBFFFFFFF))
    }
}

/* ── Song rows ── */

/**
 * A song of an album (board "Android · Album"): its number (the bars while it's the one playing), the title (in the
 * accent then, the row lit), "artists · 3:42" and the "…" button.
 */
@Composable
fun TrackRow(
    number: String,
    title: String,
    sub: String,
    current: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
            .background(if (current) Color(0x0FFFFFFF) else Color.Transparent).tap(onClick = onClick).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            if (current) EqBars(playing) else SText(number, size = 13.sp, weight = 700, color = SeedyColors.Text3, maxLines = 1)
        }
        RowTexts(title, sub, current, accent, Modifier.weight(1f))
        MoreButton(onMore)
    }
}

/**
 * A song with its cover (board "Android · Artist" Popular; playlists and the Songs tab): the number when given, the
 * cover, the title (in the accent while it plays, the row lit), the line under it and "…".
 */
@Composable
fun CoverRow(
    track: BaseItemDto,
    number: String?,
    sub: String,
    current: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    Row(
        modifier.fillMaxWidth().heightIn(min = 58.dp).clip(RoundedCornerShape(12.dp))
            .background(if (current) Color(0x0FFFFFFF) else Color.Transparent).tap(onClick = onClick).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (number != null) SText(number, Modifier.width(14.dp), size = 13.sp, weight = 700, color = SeedyColors.Text3, maxLines = 1)
        ItemCover(track, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), width = 104)
        RowTexts(track.name.orEmpty(), sub, current, accent, Modifier.weight(1f))
        MoreButton(onMore)
    }
}

@Composable
private fun RowTexts(title: String, sub: String, current: Boolean, accent: Color, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        SText(title, size = 14.sp, weight = 700, color = if (current) accent else SeedyColors.White, maxLines = 1)
        if (sub.isNotEmpty()) SText(sub, size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
    }
}

@Composable
private fun MoreButton(onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).tap(onClick = onClick), contentAlignment = Alignment.Center) {
        SIcon(MusicIcons.More, size = 18.dp, color = SeedyColors.Text2)
    }
}

/** "Ada Vale · 3:42": a song's artists and length. */
fun trackSub(track: BaseItemDto): String = io.github.arkmind.seedy.core.music.dot(artistsOf(track), io.github.arkmind.seedy.core.music.trackTime(track))

/** A light 48 px button with an icon and a label (the artist's Play), or a dark one ([light] off: Radio). */
@Composable
fun PillAction(label: String, icon: IconDef, onClick: () -> Unit, modifier: Modifier = Modifier, light: Boolean = false, iconSize: Dp = 20.dp) {
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(14.dp)).background(if (light) SeedyColors.Inverse else SeedyColors.Surface).tap(onClick = onClick)
            .padding(horizontal = if (light) 22.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SIcon(icon, size = iconSize, color = if (light) SeedyColors.InkInverse else SeedyColors.White)
        SText(label, size = if (light) 15.sp else 14.sp, weight = if (light) 800 else 700, color = if (light) SeedyColors.InkInverse else SeedyColors.White, maxLines = 1)
    }
}

/** A square dark 48 px button (Shuffle). */
@Composable
fun SquareAction(icon: IconDef, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 48.dp, iconSize: Dp = 21.dp) {
    Box(modifier.size(size).clip(RoundedCornerShape(14.dp)).background(SeedyColors.Surface).tap(onClick = onClick), contentAlignment = Alignment.Center) {
        SIcon(icon, size = iconSize, color = SeedyColors.White)
    }
}

/** CSS `linear-gradient([angle]deg, colors…)` over the whole box (0deg points up, 90deg right). */
fun Modifier.cssGradient(angle: Float, colors: List<Color>, stops: List<Float>? = null): Modifier = this.drawBehind {
    val rad = Math.toRadians(angle.toDouble())
    val dx = kotlin.math.sin(rad).toFloat()
    val dy = -kotlin.math.cos(rad).toFloat()
    val len = kotlin.math.abs(size.width * dx) + kotlin.math.abs(size.height * dy)
    val half = Offset(dx, dy) * (len / 2f)
    val brush = if (stops != null) Brush.linearGradient(*stops.zip(colors).toTypedArray(), start = center - half, end = center + half)
    else Brush.linearGradient(colors, start = center - half, end = center + half)
    drawRect(brush)
}

/** A stand-in block while a section loads. */
@Composable
fun Skeleton(modifier: Modifier, radius: Dp = 11.dp) = Box(modifier.clip(RoundedCornerShape(radius)).background(SeedyColors.Row))
