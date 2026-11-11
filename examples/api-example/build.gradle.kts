plugins {
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
}

version = "1.0.0"
group = "dev.example"

repositories {
    maven("https://maven.fabricmc.net/")
    maven("https://api.modrinth.com/maven")
}

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc.fabric-api:fabric-api:0.161.0+26.2")

    compileOnly("maven.modrinth:irenehotwire:VERSION")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
