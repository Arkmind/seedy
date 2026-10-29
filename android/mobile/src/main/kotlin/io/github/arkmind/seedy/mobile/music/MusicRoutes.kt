package io.github.arkmind.seedy.mobile.music

import androidx.compose.runtime.Composable
import io.github.arkmind.seedy.ui.components.NotYet
import java.util.UUID

/** Board "Android · Music": a music library's Home, Albums, Artists, Playlists and Songs. */
@Composable
fun MusicRoute(id: UUID) = NotYet("Music")

/** Board "Android · Album": an album or a playlist. */
@Composable
fun AlbumRoute(id: UUID) = NotYet("Album")

/** Board "Android · Artist". */
@Composable
fun ArtistRoute(id: UUID) = NotYet("Artist")

/** Board "Android · Now playing": the full-screen music player. */
@Composable
fun NowPlayingRoute() = NotYet("Now playing")
