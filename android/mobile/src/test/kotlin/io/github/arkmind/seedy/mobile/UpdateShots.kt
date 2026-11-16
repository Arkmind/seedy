package io.github.arkmind.seedy.mobile

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.arkmind.seedy.core.update.ReleaseNotes
import io.github.arkmind.seedy.core.update.UpdateOffer
import io.github.arkmind.seedy.mobile.update.UpdateScreen
import io.github.arkmind.seedy.ui.theme.SeedyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The update screen with the board's release, read from a release description (board "Android · Update ready"). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-mdpi", sdk = [35])
class UpdateShots {
    @get:Rule val compose = createComposeRule()

    private val body = """
        # What's Changed
        - <!-- icon:play_button --> Your own “Still watching?” rules
          2–4 episodes, 90 min–3 h, or 1–3 movies
        - <!-- icon:mobile --> See where you’re signed in
          Sign out a phone or TV you no longer use
        - <!-- icon:sparkles --> Medu moved in
          A little jellyfish for the quiet moments

        **Full changelog**: https://github.com/Arkmind/seedy/compare/v1.6.0...v1.7.0
    """.trimIndent()

    @Test
    fun updateReady() {
        val offer = UpdateOffer("1.7", 1_070_099, ReleaseNotes.parse(body), bytes = 18_400_000, fromPlay = true)
        compose.setContent { SeedyTheme { UpdateScreen(offer, installed = "1.6", onUpdate = {}, onLater = {}, onChanges = {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/AndroidUpdate.png")
    }
}
