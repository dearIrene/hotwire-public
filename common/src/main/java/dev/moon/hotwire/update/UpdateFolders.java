package dev.moon.hotwire.update;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    public List<Path> swapperLibraries() {
        long pid = ProcessHandle.current().pid();
        return List.of(this.swapper.resolve("gson-" + pid + ".jar"), this.swapper.resolve("slf4j-" + pid + ".jar"));
    }

    public Path swapperFolders() {
        return this.swapper.resolve(JarSwapper.FOLDERS + ProcessHandle.current().pid() + ".txt");
    }

    public Set<Path> swapperFiles() {
        Set<Path> files = new HashSet<>(this.swapperLibraries());
        files.addAll(List.of(this.swapperJar(), this.swapperLog(), this.swapperFolders()));
        return files;
    }
}
