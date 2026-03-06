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

rootProject.name = "MelangeLmProxyDemo"

// Include the proxy library as a local module
include(":proxy")
project(":proxy").projectDir = file("../proxy-android/proxy")

include(":app")
