package dev.moon.hotwire.engine;

import dev.moon.hotwire.cache.CacheStore;
import dev.moon.hotwire.config.HotwireSettings;
import dev.moon.hotwire.dev.DevMod;
import dev.moon.hotwire.dev.DevSession;
import dev.moon.hotwire.jar.JarNames;
import dev.moon.hotwire.jar.JarScans;
import dev.moon.hotwire.jar.ModIndex;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.jar.ModJarReader;
import dev.moon.hotwire.redefine.HandlerGuards;
import dev.moon.hotwire.redefine.TripwireSites;
import dev.moon.hotwire.registration.GameLibraries;
import dev.moon.hotwire.report.StartTimes;
import dev.moon.hotwire.switching.ModMixins;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EarlyModsReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(EarlyModsReader.class);
    private static volatile @Nullable CompletableFuture<EarlyRead> ahead;
    private static boolean started;

    private EarlyModsReader() {
    }

    record Read(JarScans scans, ModIndex index) {
    }

    private record EarlyRead(String folder, InstalledMods installed) {
    }

    public static synchronized void start() {
        if (started || DevSetups.get().inSetup()) {
            return;
        }
        started = true;
        FabricLoader loader = FabricLoader.getInstance();
        CompletableFuture<EarlyRead> reading = new CompletableFuture<>();
        Thread reader = new Thread(() -> {
            long began = System.nanoTime();
            try {
                String folder = describe(ModsFolder.find(loader));
                InstalledMods installed = readInstalledMods(loader);
                reading.complete(new EarlyRead(folder, installed));
                registerTripwireClasses(installed, loader);
            } catch (IOException | RuntimeException e) {
                reading.completeExceptionally(e);
            }
            StartTimes.add("read the installed mods ahead, own thread", began);
        }, "hotwire-mods");
        reader.setDaemon(true);
        ahead = reading;
        reader.start();
    }

    public static InstalledMods take(FabricLoader loader) throws IOException {
        CompletableFuture<EarlyRead> reading = ahead;
        ahead = null;
        if (reading != null) {
            try {
                EarlyRead read = reading.join();
                if (read.folder().equals(describe(ModsFolder.find(loader)))) {
                    return read.installed();
                }
                LOGGER.info("[Hotwire] the mods folder changed while the game started: it is read again");
            } catch (CompletionException | CancellationException | IOException e) {
                LOGGER.debug("[Hotwire] the mods folder was not read ahead: {}", String.valueOf(e.getCause()));
            }
        }
        return readInstalledMods(loader);
    }

    private static String describe(Path mods) throws IOException {
        try (Stream<Path> files = Files.list(mods)) {
            return files.map(file -> {
                try {
                    return file.getFileName() + "|" + Files.size(file) + "|" + Files.getLastModifiedTime(file);
                } catch (IOException e) {
                    return file.getFileName() + "|?";
                }
            }).sorted().collect(Collectors.joining(","));
        }
    }

    private static boolean isLibrary(ModMetadata mod) {
        CustomValue menu = mod.getCustomValue("modmenu");
        CustomValue badges = menu != null && menu.getType() == CustomValue.CvType.OBJECT
                ? menu.getAsObject().get("badges") : null;
        if (badges != null && badges.getType() == CustomValue.CvType.ARRAY) {
            for (CustomValue badge : badges.getAsArray()) {
                if (badge.getType() == CustomValue.CvType.STRING && badge.getAsString().equals("library")) {
                    return true;
                }
            }
        }
        return mod.containsCustomValue("fabric-loom:generated");
    }

    private static boolean isNewer(Path file, Path than) {
        try {
            return Files.getLastModifiedTime(file).compareTo(Files.getLastModifiedTime(than)) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    static InstalledMods readInstalledMods(FabricLoader loader) throws IOException {
        Path mods = ModsFolder.find(loader);
        Read ahead = open();
        JarScans scans = ahead.scans();
        ModIndex.Snapshot snapshot = ahead.index().scan(mods);

        for (ModIndex.Failure failure : snapshot.failures()) {
            ModJarException why = failure.reason();
            if (why.getReason() != ModJarException.Reason.NOT_A_MOD) {
                LOGGER.warn("[Hotwire] not read from the mods folder: {}{}", why.getMessage(),
                        why.getCause() == null ? "" : " (" + why.getCause() + ")");
            }
        }
        List<ModJar> jars = new ArrayList<>();
        Map<String, Path> jarOf = new HashMap<>();
        Set<String> topLevel = new HashSet<>();
        Map<String, ModJar> disabled = new HashMap<>();
        for (ModIndex.Entry entry : snapshot.mods()) {
            if (!entry.disabled()) {
                String id = entry.jar().manifest().id();
                jars.add(entry.jar());
                topLevel.add(id);
                jarOf.put(id, entry.file());
            }
        }

        Map<String, Path> disabledFile = new HashMap<>();
        for (ModIndex.Entry entry : snapshot.mods()) {
            String id = entry.jar().manifest().id();
            if (!entry.disabled() || jarOf.containsKey(id) || loader.isModLoaded(id) || ModPolicy.isHotwire(id)) {
                continue;
            }
            Path before = disabledFile.get(id);
            if (before == null || isNewer(entry.file(), before)) {
                disabledFile.put(id, entry.file());
                disabled.put(id, entry.jar());
            }
        }
        disabledFile.forEach((id, file) -> {
            topLevel.add(id);
            jarOf.put(id, file);
        });
        for (ModContainer container : loader.getAllMods()) {
            String id = container.getMetadata().getId();
            Path jar = InstalledMods.findLoadedFrom(container, mods);
            if (jar == null || jarOf.containsKey(id) || jarOf.containsValue(jar) || !ModPolicy.isSwitchable(id)) {
                continue;
            }
            try {
                ModJar read = ModJarReader.read(jar, scans);
                jars.add(read);
                jarOf.put(id, jar);

                if (!isLibrary(container.getMetadata())) {
                    topLevel.add(id);
                }
                LOGGER.info("[Hotwire] {} runs from {}, outside the mods folder", id, jar.getFileName());
            } catch (ModJarException e) {
                LOGGER.warn("[Hotwire] {} runs from {}, which could not be read: {}", id, jar, e.getMessage());
            }
        }

        boolean withHistory = DevSetups.get().inSetup() && new HotwireSettings(FabricLoader.getInstance().getConfigDir()
                .resolve("hotwire/settings.json")).isHistorySaved();
        if (DevSetups.get().inSetup()) {
            for (DevMod mod : DevMod.find()) {
                if (jarOf.containsKey(mod.id())) {
                    continue;
                }
                try {
                    Path jar = DevSession.takeStart(mod, DevSetups.getBuildFolder(), withHistory);
                    jars.add(ModJarReader.read(jar, scans));
                    topLevel.add(mod.id());
                    jarOf.put(mod.id(), jar);
                    LOGGER.info("[Hotwire] {} runs from its build folders {}", mod.id(), mod.folders());
                } catch (IOException | ModJarException e) {
                    LOGGER.warn("[Hotwire] {}'s build folders could not be read: {}", mod.id(), e.toString());
                }
            }
        }
        scans.flush();
        jars.replaceAll(jar -> dropUnloadedTwins(jar, loader));
        return new InstalledMods(jars, jarOf, topLevel, disabled);
    }

    private static ModJar dropUnloadedTwins(ModJar jar, FabricLoader loader) {
        Map<String, Long> count = jar.nested().stream()
                .collect(Collectors.groupingBy(nested -> nested.manifest().id(), Collectors.counting()));
        List<ModJar> nested = jar.nested().stream().filter(one -> count.getOrDefault(one.manifest().id(), 0L) == 1
                || loader.getModContainer(one.manifest().id()).map(mod -> mod.getMetadata().getVersion()
                        .getFriendlyString().equals(one.manifest().version())).orElse(true))
                .map(one -> dropUnloadedTwins(one, loader)).toList();
        return new ModJar(jar.name(), jar.manifest(), jar.patches(), nested, jar.nestedNotMods(), jar.accessWidener());
    }

    static void registerTripwireClasses(InstalledMods installed, FabricLoader loader) {
        if (!HandlerGuards.ENABLED) {
            return;
        }
        Set<String> loadedIds = new HashSet<>();
        loader.getAllMods().forEach(mod -> loadedIds.add(mod.getMetadata().getId()));
        ModMixins.readAll(installed.getGraphJars(), loadedIds).forEach((id, mod) -> {
            if (ModPolicy.isSwitchable(id)) {
                TripwireSites.registerClasses(id, mod.owns().stream()
                        .filter(name -> !GameLibraries.has(name, null)).collect(Collectors.toSet()));
            }
        });
    }

    static Read open() {
        JarScans scans = new JarScans(CacheStore.open(CacheLimit.findCacheFolder().resolve("jars"), "jars",
                CacheLimit.JAR_SCANS_BYTES));
        JarNames.open(CacheStore.open(CacheLimit.findCacheFolder().resolve("names"), "names",
                CacheLimit.JAR_SCANS_BYTES));
        return new Read(scans, new ModIndex(ForkJoinPool.commonPool(), scans));
    }
}
