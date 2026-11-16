package io.github.arkmind.seedy.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseNotesTest {
    private val body = """
        # What's Changed
        - <!-- icon:play_button --> Your own “Still watching?” rules
          2–4 episodes, 90 min–3 h, or 1–3 movies
          <!-- fr: Vos propres règles « Toujours là ? » | 2 à 4 épisodes, 90 min à 3 h, ou 1 à 3 films -->
        - <!-- icon:mobile --> See where you’re **signed in**
          Sign out a phone or TV you no longer use
        - <!-- icon:sparkles --> Medu moved in
          A little jellyfish for the quiet moments

        ## Fixes
        - <!-- icon:check --> Not part of the screen

        **Full changelog**: https://github.com/Arkmind/seedy/compare/v0.4.6...v0.4.7
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun readsTheWhatsChangedList() {
        val items = ReleaseNotes.parse(body)
        assertEquals(3, items.size)
        assertEquals(
            ReleaseItem(
                "play_button", "Your own “Still watching?” rules", "2–4 episodes, 90 min–3 h, or 1–3 movies",
                "Vos propres règles « Toujours là ? »", "2 à 4 épisodes, 90 min à 3 h, ou 1 à 3 films",
            ),
            items[0],
        )
        assertEquals(ReleaseItem("mobile", "See where you’re signed in", "Sign out a phone or TV you no longer use"), items[1])
        assertEquals("sparkles", items[2].icon)
    }

    @Test
    fun skipsGitHubsGeneratedLines() {
        val generated = """
            ## What's Changed
            * Feature/still watching by @Arkmind in https://github.com/Arkmind/seedy/pull/87

            **Full Changelog**: https://github.com/Arkmind/seedy/compare/v0.4.5...v0.4.6
        """.trimIndent()
        assertEquals(emptyList<ReleaseItem>(), ReleaseNotes.parse(generated))
    }

    @Test
    fun bulletsWithoutAnIconOrSubtitle() {
        val items = ReleaseNotes.parse("Intro line\n\n- Faster home\n- [Docs](https://x) moved")
        assertEquals(listOf(ReleaseItem("sparkles", "Faster home"), ReleaseItem("sparkles", "Docs moved")), items)
        assertEquals(emptyList<ReleaseItem>(), ReleaseNotes.parse(null))
    }

    @Test
    fun versionCodesMatchTheBuild() {
        assertEquals(40799L, versionCodeOf("v0.4.7"))
        assertEquals(50033L, versionCodeOf("0.5.0-beta.2"))
        assertEquals(1_020_399L, versionCodeOf("1.2.3"))
        assertNull(versionCodeOf("nightly"))
        assertEquals("0.4.7", versionNameOf(40799))
        assertEquals("0.5.0-beta.2", versionNameOf(50033))
        assertEquals("0.5.0-rc.1", versionNameOf(50062))
    }

    @Test
    fun picksTheNewestReleaseWithAnApk() {
        val json = """[
          {"tag_name":"v0.5.0-beta.1","prerelease":true,"draft":false,"body":"","html_url":"u3","assets":[{"name":"seedy-android-0.5.0-beta.1.apk","size":1,"browser_download_url":"a3"}]},
          {"tag_name":"v0.4.8","prerelease":false,"draft":false,"body":"","html_url":"u2","assets":[]},
          {"tag_name":"v0.4.7","prerelease":false,"draft":false,"body":"- <!-- icon:tv --> TV","html_url":"u1","assets":[{"name":"seedy_0.4.7.0.zip","size":5},{"name":"seedy-android-0.4.7.apk","size":18400000,"browser_download_url":"a1","digest":"sha256:abc123"}]},
          {"tag_name":"v0.4.9","draft":true,"assets":[]}
        ]"""
        val releases = Releases.parse(json)
        assertEquals(listOf("0.5.0-beta.1", "0.4.8", "0.4.7"), releases.map { it.version })
        val offer = Releases.newest(releases, versionCodeOf("0.4.6")!!)!!.offer()
        assertEquals(UpdateOffer("0.4.7", 40799, listOf(ReleaseItem("tv", "TV")), "u1", 18_400_000, false, "a1", "abc123"), offer)
        // Someone on a pre-release is offered the next one.
        assertEquals("0.5.0-beta.1", Releases.newest(releases, versionCodeOf("0.5.0-beta.0")!!)?.version)
        assertNull(Releases.newest(releases, versionCodeOf("0.4.7")!!))
    }
}
