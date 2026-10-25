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
        // v0.41 — مرآة احتياطية لصمود الشبكة: تُستشار فقط بعد فشل mavenCentral (مجموعات تنقصها المرآة تُستثنى)
        maven("https://maven.aliyun.com/repository/public") {
            content {
                excludeGroupByRegex("com\\.android.*")
                excludeGroupByRegex("com\\.google.*")
                excludeGroupByRegex("androidx.*")
            }
        }
    }
}

rootProject.name = "NovaFiles"
include(":app")
