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

rootProject.name = "DirTunnel"

// TECH.md 五、模块拆分
include(":app")
include(":feature:directory")
include(":feature:service")
include(":feature:tunnel")
include(":feature:log")
include(":feature:setting")
include(":core:ui")
include(":core:engine")
include(":core:storage")
include(":core:network")
