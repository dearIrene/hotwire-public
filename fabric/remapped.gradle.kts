plugins {
    id("net.fabricmc.fabric-loom-remap")
    id("hotwire.fabric")
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
}

stonecutter {
    replacements.string(eval(current.version, ">=1.21.11")) {
        replace("ResourceLocation", "Identifier")
    }

    replacements.string(eval(current.version, ">=26.3")) {
        replace("com.mojang.blaze3d.textures.", "com.mojang.renderpearl.api.textures.")
    }
}
