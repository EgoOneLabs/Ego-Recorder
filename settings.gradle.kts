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

rootProject.name = "EgoUVC-Phone"
include(":app")
include(":uvccamera")
project(":uvccamera").projectDir = file("third_party/uvcandroid/libuvccamera")
