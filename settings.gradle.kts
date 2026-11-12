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
        // desktop-kit is built by JitPack from its git tag; nothing else comes from there
        maven("https://jitpack.io") {
            content { includeGroup("com.github.hesaplarimdijital-coder") }
        }
    }
}

rootProject.name = "DeskMail"
include(":core", ":app")
