package dev.moon.hotwire.fabric.dev;

import dev.moon.hotwire.fabric.ModPolicy;
import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

public record DevMod(String id, String name, List<Path> folders) {
    public DevMod {
        folders = List.copyOf(folders);
    }

    private static final List<DevMod> FOUND = findAll();

    public static List<DevMod> find() {
        return FOUND;
    }

    private static List<DevMod> findAll() {
        List<DevMod> found = new ArrayList<>();
        Set<Path> others = new HashSet<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            mod.getRootPaths().stream().filter(DevMod::isFolder).map(DevMod::normalize).forEach(others::add);
        }
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            ModOrigin origin = mod.getOrigin();
            String id = mod.getMetadata().getId();

            if (origin.getKind() != ModOrigin.Kind.PATH || ModPolicy.isHotwire(id) || !ModPolicy.isSwitchable(id)
                    || origin.getPaths().isEmpty() || !origin.getPaths().stream().allMatch(DevMod::isFolder)) {
                continue;
            }
            Set<Path> folders = new LinkedHashSet<>();
            mod.getRootPaths().stream().filter(DevMod::isFolder).map(DevMod::normalize).forEach(folders::add);
            origin.getPaths().forEach(path -> folders.addAll(findClassFolders(normalize(path), others)));
            found.add(new DevMod(id, mod.getMetadata().getName(), List.copyOf(folders)));
        }
        return List.copyOf(found);
    }

    private static List<Path> findClassFolders(Path origin, Set<Path> others) {
        Path parent = origin.getParent();
        Path build = parent == null ? null : parent.getParent();
        if (build == null) {
            return List.of();
        }
        List<Path> found = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator, -1)) {
            if (entry.isBlank()) {
                continue;
            }
            Path path = normalize(Path.of(entry));
            if (path.startsWith(build) && !others.contains(path) && Files.isDirectory(path)) {
                found.add(path);
            }
        }
        return found;
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static boolean isFolder(Path path) {
        return path.getFileSystem().equals(FileSystems.getDefault()) && Files.isDirectory(path);
    }
}
