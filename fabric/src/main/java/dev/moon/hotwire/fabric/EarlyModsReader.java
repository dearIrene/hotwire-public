package dev.moon.hotwire.fabric;

import dev.moon.hotwire.cache.CacheStore;
import dev.moon.hotwire.jar.JarScans;
import dev.moon.hotwire.jar.ModIndex;
import dev.moon.hotwire.report.StartTimes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class EarlyModsReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(EarlyModsReader.class);
    private static volatile @Nullable CompletableFuture<EarlyRead> ahead;

    private EarlyModsReader() {
    }

    record Read(JarScans scans, ModIndex index) {
    }

    private record EarlyRead(String folder, InstalledMods installed) {
    }

    static void start() {
        if (DevSetups.get().inSetup()) {
            return;
        }
        FabricLoader loader = FabricLoader.getInstance();
        CompletableFuture<EarlyRead> reading = new CompletableFuture<>();
        Thread reader = new Thread(() -> {
            long began = System.nanoTime();
            try {
                String folder = describe(ModsFolder.find(loader));
                reading.complete(new EarlyRead(folder, EngineStart.readInstalledMods(loader)));
            } catch (IOException | RuntimeException e) {
                reading.completeExceptionally(e);
            }
            StartTimes.add("read the installed mods ahead, own thread", began);
        }, "hotwire-mods");
        reader.setDaemon(true);
        ahead = reading;
        reader.start();
    }

    static InstalledMods take(FabricLoader loader) throws IOException {
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
        return EngineStart.readInstalledMods(loader);
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

    static Read open() {
        JarScans scans = new JarScans(CacheStore.open(EngineStart.findCacheFolder().resolve("jars"), "jars",
                EngineStart.JAR_SCANS_BYTES));
        return new Read(scans, new ModIndex(ForkJoinPool.commonPool(), scans));
    }
}
