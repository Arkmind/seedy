package io.github.arkmind.seedy

import android.app.Application
import io.github.arkmind.seedy.core.SeedyCore

class SeedyApp : Application() {
    lateinit var core: SeedyCore
        private set

    override fun onCreate() {
        super.onCreate()
        core = SeedyCore(this, BuildConfig.VERSION_NAME)
    }
}
