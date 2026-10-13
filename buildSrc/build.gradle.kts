plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

fun plugin(id: String, version: String) = "$id:$id.gradle.plugin:$version"

dependencies {
    implementation(plugin("net.ltgt.errorprone", "5.1.1"))
    implementation(plugin("net.fabricmc.fabric-loom", "1.17-SNAPSHOT"))
    implementation(plugin("net.fabricmc.fabric-loom-remap", "1.17-SNAPSHOT"))
}
