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

rootProject.name = "nxbackup"
include(":core", ":server")
// The Android module needs the Android SDK; it is left out when its directory is absent
// (Docker build context) or when NXBACKUP_SERVER_ONLY is set (server-only CI job).
if (file("app").isDirectory && System.getenv("NXBACKUP_SERVER_ONLY") == null) include(":app")
