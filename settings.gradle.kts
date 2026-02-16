pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "ImageNext"

include(":app")

// Core modules
include(":core:model")
include(":core:common")
include(":core:network")
include(":core:database")
include(":core:security")
include(":core:data")
include(":core:sync")

// Feature modules
include(":feature:onboarding")
include(":feature:folders")
include(":feature:photos")
include(":feature:albums")
include(":feature:settings")
include(":feature:viewer")
