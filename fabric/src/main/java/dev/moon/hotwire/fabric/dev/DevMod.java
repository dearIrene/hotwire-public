package dev.moon.hotwire.fabric.dev;

import dev.moon.hotwire.fabric.ModPolicy;
import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import org.jspecify.annotations.Nullable;

public record DevMod(String id, String name, List<Path> folders) {
    public DevMod {
        folders = List.copyOf(folders);
    }

    Set<String> findStartClasses() {
        Set<String> starts = new HashSet<>();
        for (String key : List.of("main", "client")) {
            for (EntrypointContainer<Object> entry : FabricLoader.getInstance().getEntrypointContainers(key,
                    Object.class)) {
                if (entry.getProvider().getMetadata().getId().equals(this.id)) {
                    starts.add(entry.getEntrypoint().getClass().getName());
                }
            }
        }
        return starts;
    }

    private static final List<DevMod> FOUND = findAll();

    public static List<DevMod> find() {
        return FOUND;
    }

    private static List<DevMod> findAll() {
        Set<Path> owned = new HashSet<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            mod.getRootPaths().stream().filter(DevMod::isFolder).map(DevMod::normalize).forEach(owned::add);
            if (mod.getOrigin().getKind() == ModOrigin.Kind.PATH) {
                mod.getOrigin().getPaths().stream().map(DevMod::normalize).forEach(owned::add);
            }
        }
        Map<String, ModContainer> containers = new LinkedHashMap<>();
        Map<String, Set<Path>> folders = new LinkedHashMap<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            ModOrigin origin = mod.getOrigin();
            String id = mod.getMetadata().getId();

            if (origin.getKind() != ModOrigin.Kind.PATH || ModPolicy.isHotwire(id) || !ModPolicy.isSwitchable(id)
                    || origin.getPaths().isEmpty() || !origin.getPaths().stream().allMatch(DevMod::isFolder)) {
                continue;
            }
            containers.put(id, mod);
            Set<Path> own = new LinkedHashSet<>();
            mod.getRootPaths().stream().filter(DevMod::isFolder).map(DevMod::normalize).forEach(own::add);
            folders.put(id, own);
        }

        for (Path loose : findLoose(owned)) {
            String best = null;
            int bestDepth = -1;
            for (Map.Entry<String, ModContainer> entry : containers.entrySet()) {
                for (Path origin : entry.getValue().getOrigin().getPaths()) {
                    Path normal = normalize(origin);
                    Path project = findProject(normal);
                    int depth = findCommonDepth(normal, loose);
                    if (project != null && loose.startsWith(project) && depth > bestDepth) {
                        best = entry.getKey();
                        bestDepth = depth;
                    }
                }
            }
            if (best != null) {
                folders.get(best).add(loose);
            }
        }
        List<DevMod> found = new ArrayList<>();
        containers.forEach((id, mod) -> found.add(new DevMod(id, mod.getMetadata().getName(),
                List.copyOf(folders.get(id)))));
        return List.copyOf(found);
    }

    public List<Path> findSourceRoots() {
        Set<Path> roots = new LinkedHashSet<>();
        for (Path folder : this.folders) {
            Path module = findModule(folder);
            Path src = module == null ? null : module.resolve("src");
            if (src != null && Files.isDirectory(src)) {
                roots.add(src);
            } else {
                Path project = findProject(normalize(folder));
                if (project != null) {
                    roots.add(project);
                }
            }
        }
        return List.copyOf(roots);
    }

    public @Nullable Path findSourceProject() {
        for (Path folder : this.folders) {
            Path project = findProject(normalize(folder));
            if (project != null) {
                return project;
            }
            Path module = findModule(folder);
            if (module != null) {
                return module;
            }
        }
        return null;
    }

    private static @Nullable Path findModule(Path folder) {
        Path at = normalize(folder);
        for (int up = 0; at != null && up < 8; up++, at = at.getParent()) {
            Path name = at.getFileName();
            if (name != null && Set.of("build", "out", "bin").contains(name.toString())) {
                return at.getParent();
            }
        }
        return null;
    }

    private static List<Path> findLoose(Set<Path> owned) {
        List<Path> loose = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator, -1)) {
            if (entry.isBlank()) {
                continue;
            }
            Path path = normalize(Path.of(entry));
            boolean jar = path.toString().endsWith(".jar") && Files.isRegularFile(path);
            if (!owned.contains(path) && (jar || Files.isDirectory(path))) {
                loose.add(path);
            }
        }
        return loose;
    }

    static @Nullable Path findProject(Path origin) {
        Path at = origin.getParent();
        for (int up = 0; at != null && up < 8; up++, at = at.getParent()) {
            if (Files.isRegularFile(at.resolve("settings.gradle.kts"))
                    || Files.isRegularFile(at.resolve("settings.gradle"))) {
                return at;
            }
        }
        return null;
    }

    private static int findCommonDepth(Path a, Path b) {
        int depth = 0;
        while (depth < a.getNameCount() && depth < b.getNameCount() && a.getName(depth).equals(b.getName(depth))) {
            depth++;
        }
        return depth;
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static boolean isFolder(Path path) {
        return path.getFileSystem().equals(FileSystems.getDefault()) && Files.isDirectory(path);
    }
}
