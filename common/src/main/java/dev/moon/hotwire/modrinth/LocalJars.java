package dev.moon.hotwire.modrinth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LocalJars {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalJars.class);
    private static final List<String> MODS = List.of("mods", ".minecraft/mods", "minecraft/mods");

    private final @Nullable Path gameDir;
    private final List<Path> launcherHomes;

    public LocalJars(@Nullable Path gameDir, List<Path> launcherHomes) {
        this.gameDir = gameDir;
        this.launcherHomes = launcherHomes;
    }

    public static final LocalJars NONE = new LocalJars(null, List.of());

    public static List<Path> findLauncherHomes() {
        List<Path> out = new ArrayList<>();
        String home = System.getProperty("user.home");
        String appData = System.getenv("APPDATA");
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
        Path data = Path.of(home, ".local", "share");
        if (appData != null) {
            data = Path.of(appData);
        } else if (mac) {
            data = Path.of(home, "Library", "Application Support");
        }
        out.add(data.resolve("ModrinthApp").resolve("profiles"));
        out.add(data.resolve("PrismLauncher").resolve("instances"));
        out.add(data.resolve("ATLauncher").resolve("instances"));
        out.add(Path.of(home, "curseforge", "minecraft", "Instances"));
        if (appData != null) {
            out.add(data.resolve(".minecraft"));
        } else {
            out.add(mac ? data.resolve("minecraft") : Path.of(home, ".minecraft"));
        }
        return out;
    }

    public byte @Nullable [] find(String fileName, long size, String sha512) {
        long began = System.nanoTime();
        for (Path folder : this.findModsFolders()) {
            for (Path file : list(folder)) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".jar") && !name.endsWith(".jar.disabled")) {
                    continue;
                }
                try {
                    if (Files.size(file) != size) {
                        continue;
                    }
                    byte[] bytes = Files.readAllBytes(file);
                    if (ModrinthClient.hashOf(bytes).equalsIgnoreCase(sha512)) {
                        LOGGER.info("[Hotwire] {} was read from {} in place of a download ({} ms)", fileName,
                                folder, (System.nanoTime() - began) / 1_000_000);
                        return bytes;
                    }
                } catch (IOException | UncheckedIOException e) {
                }
            }
        }
        return null;
    }

    List<Path> findModsFolders() {
        Set<Path> homes = new LinkedHashSet<>();
        Path parent = this.gameDir == null ? null : this.gameDir.toAbsolutePath().getParent();
        if (parent != null) {
            homes.add(parent);
            if (parent.getParent() != null) {
                homes.add(parent.getParent());
            }
        }
        homes.addAll(this.launcherHomes);
        Set<Path> out = new LinkedHashSet<>();
        for (Path home : homes) {
            List<Path> instances = new ArrayList<>(list(home));
            instances.addFirst(home);
            for (Path instance : instances) {
                for (String mods : MODS) {
                    Path folder = instance.resolve(mods);
                    if (Files.isDirectory(folder)) {
                        out.add(folder.normalize());
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    private static List<Path> list(Path folder) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.toList();
        } catch (IOException | UncheckedIOException e) {
            return List.of();
        }
    }
}
