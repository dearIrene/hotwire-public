package dev.moon.hotwire.update;

import java.nio.file.Path;

public record UpdateFolders(Path mods, Path queueFile, Path staging, Path replaced, Path swapper) {
    public static UpdateFolders of(Path gameDir) {
        Path hotwire = gameDir.resolve("hotwire");
        return new UpdateFolders(gameDir.resolve("mods"), hotwire.resolve("updates.json"), hotwire.resolve("staging"),
                hotwire.resolve("replaced"), hotwire.resolve("swapper"));
    }
}
