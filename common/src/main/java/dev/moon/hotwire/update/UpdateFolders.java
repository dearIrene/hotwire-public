package dev.moon.hotwire.update;

import java.nio.file.Path;

public record UpdateFolders(Path mods, Path queueFile, Path staging, Path replaced, Path swapper) {
    public static UpdateFolders of(Path gameDir) {
        return ofMods(gameDir.resolve("mods"));
    }

    public static UpdateFolders ofMods(Path mods) {
        Path folder = mods.toAbsolutePath().normalize();
        Path beside = folder.getParent();
        Path hotwire = (beside == null ? folder : beside).resolve("hotwire");
        return new UpdateFolders(mods, hotwire.resolve("updates.json"), hotwire.resolve("staging"),
                hotwire.resolve("replaced"), hotwire.resolve("swapper"));
    }
}
