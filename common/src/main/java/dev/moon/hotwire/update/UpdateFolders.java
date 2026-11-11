package dev.moon.hotwire.update;

import java.nio.file.Path;

public record UpdateFolders(Path mods, Path queueFile, Path staging, Path replaced, Path swapper) {
    public static UpdateFolders ofMods(Path mods) {
        Path folder = mods.toAbsolutePath().normalize();
        Path beside = folder.getParent();
        Path hotwire = (beside == null ? folder : beside).resolve("hotwire");
        return new UpdateFolders(mods, hotwire.resolve("updates.json"), hotwire.resolve("staging"),
                hotwire.resolve("replaced"), hotwire.resolve("swapper"));
    }

    public Path swapperJar() {
        return this.swapper.resolve("hotwire-" + ProcessHandle.current().pid() + ".jar");
    }

    public Path swapperLog() {
        return this.swapper.resolve("swapper-" + ProcessHandle.current().pid() + ".log");
    }
}
