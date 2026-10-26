package dev.moon.hotwire.jar;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public enum JarKind {
    FABRIC, FORGE, NEOFORGE, QUILT, RESOURCE_PACK, SHADER_PACK, DATA_PACK, NOT_A_MOD, NOT_A_ZIP;

    public static JarKind of(Path file) {
        Set<String> names = new HashSet<>();
        boolean assets = false;
        boolean data = false;
        boolean shaders = false;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
                String name = entries.nextElement().getName();
                names.add(name);
                assets |= name.startsWith("assets/");
                data |= name.startsWith("data/");
                shaders |= name.startsWith("shaders/");
            }
        } catch (IOException | RuntimeException e) {
            return NOT_A_ZIP;
        }
        if (names.contains("fabric.mod.json")) {
            return FABRIC;
        }
        if (names.contains("quilt.mod.json")) {
            return QUILT;
        }
        if (names.contains("META-INF/neoforge.mods.toml")) {
            return NEOFORGE;
        }
        if (names.contains("META-INF/mods.toml") || names.contains("mcmod.info")) {
            return FORGE;
        }
        if (shaders) {
            return SHADER_PACK;
        }
        if (names.contains("pack.mcmeta")) {
            return data && !assets ? DATA_PACK : RESOURCE_PACK;
        }
        return NOT_A_MOD;
    }
}
