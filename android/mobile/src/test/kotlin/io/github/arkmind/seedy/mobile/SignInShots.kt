package io.github.arkmind.seedy.mobile

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.arkmind.seedy.core.data.SavedServer
import io.github.arkmind.seedy.core.feature.AddServerViewModel
import io.github.arkmind.seedy.core.feature.PasswordViewModel
import io.github.arkmind.seedy.core.feature.ProfilesViewModel
import io.github.arkmind.seedy.mobile.signin.AddServerScreen
import io.github.arkmind.seedy.mobile.signin.PasswordScreen
import io.github.arkmind.seedy.mobile.signin.ProfilesScreen
import io.github.arkmind.seedy.mobile.signin.SignInFor
import io.github.arkmind.seedy.ui.theme.SeedyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The sign-in screens with the boards' data, at the boards' size (390×844 at 1×), for comparing with the boards. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-mdpi", sdk = [35])
class SignInShots {
    @get:Rule val compose = createComposeRule()

    private val server = SavedServer(id = "s", name = "Arky's server", address = "http://192.168.1.20:8096")

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { SeedyTheme(content = content) }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private fun profile(id: String, name: String, colour: Int, session: Boolean) =
        ProfilesViewModel.Profile(id, name, imageUrl = null, hasSession = session, account = null, colour = colour)

    @Test
    fun whoIsWatching() = shot("AndroidLogin") {
        ProfilesScreen(
            brand = "Seedy",
            server = server,
            state = ProfilesViewModel.State(
                loading = false,
                profiles = listOf(profile("a", "Arky", 0, false), profile("s", "Sam", 1, true), profile("m", "Mia", 2, true), profile("k", "Kids", 3, true)),
                servers = listOf(server),
                quickConnect = true,
            ),
            onProfile = {}, onAddAccount = {}, onQuickConnect = {}, onServer = {}, onAddServer = {},
        )
    }

    @Test
    fun password() = shot("AndroidLoginPassword") {
        PasswordScreen(
            server = server,
            profile = SignInFor("Arky", null, 0),
            state = PasswordViewModel.State(userName = "Arky", password = "northbound"),
            onBack = {}, onUserName = {}, onPassword = {}, onRemember = {}, onSignIn = {}, onForgot = {}, onQuickConnect = {},
        )
    }

    @Test
    fun addServer() = shot("AndroidAddServer") {
        AddServerScreen(
            state = AddServerViewModel.State(found = listOf(AddServerViewModel.Found("Arky's server", "http://192.168.1.20:8096")), searching = false),
            onAdd = {}, onSearch = {}, onBack = null,
        )
    }
}
