package dev.moon.hotwire.fabric.screen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ScreenPreloader {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScreenPreloader.class);
    private static final long DELAY_SECONDS = 15;

    private static final List<String> FOLDERS = List.of("dev/moon/hotwire/ui", "dev/moon/hotwire/fabric/screen");
    private static final String LEFT_OUT = "dev/moon/hotwire/ui/dev/";

    private ScreenPreloader() {
    }

    @SuppressWarnings("ThreadPriorityCheck")
    public static void start() {
        Thread.ofPlatform().name("hotwire-screen-ahead").daemon().priority(Thread.MIN_PRIORITY).start(() -> {
            try {
                TimeUnit.SECONDS.sleep(DELAY_SECONDS);
                long began = System.nanoTime();
                int loaded = load();
                LOGGER.info("[Hotwire] the screen's {} classes were loaded ahead in {} ms", loaded,
                        (System.nanoTime() - began) / 1_000_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static int load() {
        ClassLoader loader = ScreenPreloader.class.getClassLoader();
        int loaded = 0;
        for (Path root : FabricLoader.getInstance().getModContainer("hotwire").map(mod -> mod.getRootPaths())
                .orElse(List.of())) {
            for (String folder : FOLDERS) {
                Path start = root.resolve(folder);
                if (!Files.isDirectory(start)) {
                    continue;
                }
                List<String> names;
                try (Stream<Path> files = Files.walk(start)) {
                    names = files.map(file -> root.relativize(file).toString().replace('\\', '/'))
                            .filter(name -> name.endsWith(".class") && !name.startsWith(LEFT_OUT)).toList();
                } catch (IOException | RuntimeException e) {
                    LOGGER.info("[Hotwire] the screen's classes could not be listed: {}", e.toString());
                    continue;
                }
                for (String name : names) {
                    try {
                        Class.forName(name.substring(0, name.length() - ".class".length()).replace('/', '.'), false,
                                loader);
                        loaded++;
                    } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
                    }
                }
            }
        }
        return loaded;
    }
}
