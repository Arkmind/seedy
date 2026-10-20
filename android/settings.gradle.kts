pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "seedy"

// core: Jellyfin + Seedy data, sessions, i18n · player: Media3 playback, device profile, reports · ui: theme and shared
// composables · mobile / tv: the two screen sets · app: the single APK that opens one or the other.
include(":core", ":player", ":ui", ":mobile", ":tv", ":app")
