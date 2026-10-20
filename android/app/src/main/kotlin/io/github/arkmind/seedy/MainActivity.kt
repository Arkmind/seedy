package io.github.arkmind.seedy

import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.session.SessionState
import io.github.arkmind.seedy.mobile.MobileApp
import io.github.arkmind.seedy.tv.TvApp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** One activity for phones and TVs: the TV screens on a television, the phone screens everywhere else. */
class MainActivity : ComponentActivity() {
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !ready }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val core = (application as SeedyApp).core
        val tv = isTelevision()

        lifecycleScope.launch {
            // The language this device showed last, so the first screen is already in it; then the session.
            I18n.load(this@MainActivity, I18n.resolve(core.store.current().lang))
            setContent { if (tv) TvApp(core) else MobileApp(core) }
            ready = true
            if (core.sessions.state.value == SessionState.Starting) core.sessions.start()
        }

        // The profile's language (Settings · Display) wins once someone is signed in.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                core.sessions.state.collect { s ->
                    val session = (s as? SessionState.SignedIn)?.session ?: return@collect
                    val pref = session.seedy?.prefs?.custom?.get("display.language")?.jsonPrimitive?.contentOrNull
                    val lang = I18n.resolve(pref)
                    if (core.store.current().lang != lang.code) core.store.update { it.copy(lang = lang.code) }
                    if (lang != I18n.lang) {
                        I18n.load(this@MainActivity, lang)
                        recreate()
                    }
                }
            }
        }
    }

    private fun isTelevision(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}
