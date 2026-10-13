pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.kikugie.dev/snapshots")
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9"
}

rootProject.name = "hotwire"

include("common")

stonecutter {
    create("fabric") {
        listOf("26.3", "26.2", "26.1", "1.21.11", "1.21.10", "1.21.8", "1.21.5", "1.21.4", "1.21.1").forEach { minecraft ->
            version(minecraft, minecraft).buildscript =
                if (eval(minecraft, ">=26.1")) "build.gradle.kts" else "remapped.gradle.kts"
        }
    }
}
