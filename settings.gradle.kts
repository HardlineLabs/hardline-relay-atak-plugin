pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    // The official local TAK build plugin adds its SDK flatDir repository.
    repositoriesMode.set(if (providers.gradleProperty("withAtak").orNull == "true")
        RepositoriesMode.PREFER_PROJECT else RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.meshtastic.Meshtastic-Android") }
        }
    }
}
rootProject.name = "HardlineRelay"
include(":core", ":app")
if (providers.gradleProperty("withAtak").orNull == "true") include(":atak-plugin")
