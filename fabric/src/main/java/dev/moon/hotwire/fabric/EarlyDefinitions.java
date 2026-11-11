package dev.moon.hotwire.fabric;

import dev.moon.hotwire.cache.CacheKey;
import dev.moon.hotwire.cache.CacheStore;
import dev.moon.hotwire.config.AtomicFiles;
import dev.moon.hotwire.config.HotwireSettings;
import dev.moon.hotwire.fabric.dev.DevSession;
import dev.moon.hotwire.fabric.report.HotwireLog;
import dev.moon.hotwire.jar.ZipBytes;
import dev.moon.hotwire.jvm.JvmAccess;
import dev.moon.hotwire.jvm.JvmAccessException;
import dev.moon.hotwire.redefine.HandlerGuards;
import dev.moon.hotwire.redefine.LoadedClassBytes;
import dev.moon.hotwire.redefine.OpenGameClasses;
import dev.moon.hotwire.redefine.SiteCache;
import dev.moon.hotwire.report.StartTimes;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EarlyDefinitions implements PreLaunchEntrypoint {
    private static final Logger LOGGER = LoggerFactory.getLogger(EarlyDefinitions.class);
    private static volatile @Nullable Instrumentation instrumentation;
    private static volatile @Nullable ClassFileTransformer early;
    private static final long SITES_CAP = 24L << 20;

    @Override
    public void onPreLaunch() {
        StartTimes.mark("Hotwire's pre-launch begins");
        long began = System.nanoTime();
        this.watch();
        StartTimes.add("pre-launch", began);
        StartTimes.mark("Hotwire's pre-launch ends");
    }

    @SuppressWarnings("ReferenceEquality")
    private void watch() {
        HotwireLog.install(FabricLoader.getInstance().getGameDir().resolve("hotwire").resolve("logs"));

        if (DevSetups.get().inSetup()) {
            DevSession.takeEarlySources(DevSetups.getBuildFolder());
        }

        if (System.getProperty("hotwire.guards") == null && new HotwireSettings(FabricLoader.getInstance()
                .getConfigDir().resolve("hotwire/settings.json")).isRebuildEverySwitch(FabricLoader.getInstance()
                .isDevelopmentEnvironment())) {
            System.setProperty("hotwire.guards", "false");
        }
        ClassLoader game = EarlyDefinitions.class.getClassLoader();
        try {
            Path jar = FabricLoader.getInstance().getModContainer("hotwire")
                    .flatMap(mod -> mod.getOrigin().getPaths().stream().findFirst()).orElse(null);
            if (jar == null) {
                return;
            }
            long reach = System.nanoTime();
            Instrumentation found = JvmAccess.getInstrumentation(jar);
            StartTimes.add("pre-launch: reach the JVM", reach);

            HandlerGuards.readOriginalsWith(name -> {
                URL place = game.getResource(name + ".class");
                if (place == null) {
                    return null;
                }
                try (InputStream in = place.openStream()) {
                    return in.readAllBytes();
                } catch (IOException e) {
                    return null;
                }
            });
            ClassFileTransformer watcher = new ClassFileTransformer() {
                @Override
                public byte @Nullable [] transform(@Nullable ClassLoader loader, @Nullable String name,
                        @Nullable Class<?> redefining, @Nullable ProtectionDomain domain, byte[] bytes) {
                    if (name == null || redefining != null || loader != game) {
                        return null;
                    }

                    long began = System.nanoTime();
                    byte[] made = HandlerGuards.ENABLED ? SiteCache.rewrite(name, bytes) : null;
                    long rewritten = System.nanoTime();
                    byte[] recorded = made == null ? bytes : made;
                    boolean written = LoadedClassBytes.recordDefinedInGame(name, recorded, loader);
                    StartTimes.add("early watcher: call sites", began, rewritten);
                    if (made != null) {
                        StartTimes.add("early watcher: call sites, classes given sites", began, rewritten);
                    }
                    StartTimes.add("early watcher: keep the bytes", rewritten);
                    StartTimes.addBytes("early watcher: keep the bytes", written ? recorded.length : 0);
                    return made;
                }
            };

            found.addTransformer(new OpenGameClasses(game));
            found.addTransformer(watcher);
            instrumentation = found;
            early = watcher;
            GameClassIndex.begin(found, game);
            if (HandlerGuards.ENABLED && !DevSetups.get().inSetup() && game.getParent() instanceof URLClassLoader jars
                    && Boolean.parseBoolean(System.getProperty("hotwire.siteCache", "true"))) {
                Thread.ofPlatform().daemon().name("hotwire-sites-open").start(() -> openSites(jars.getURLs()));
            }
        } catch (JvmAccessException | RuntimeException | LinkageError e) {
            LOGGER.debug("[Hotwire] classes are not kept from the start: {}", e.toString());
        }
    }

    private static void openSites(URL[] path) {
        try {
            CacheKey.Builder stamp = CacheKey.of("the loader's jars");

            Path temp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();

            List<String> told = new ArrayList<>();
            for (URL url : path) {
                Path jar = Path.of(url.toURI());
                if (jar.toAbsolutePath().normalize().startsWith(temp)) {
                    continue;
                }
                BasicFileAttributes file = Files.readAttributes(jar, BasicFileAttributes.class);
                if (!file.isRegularFile()) {
                    return;
                }
                long content;
                try {
                    content = ZipBytes.findDirectoryCrc(jar);
                } catch (IOException e) {
                    content = file.lastModifiedTime().toMillis();
                }
                stamp.add(file.size()).add(content);
                told.add(jar.getFileName() + " " + file.size() + " " + Long.toHexString(content));
            }
            Path folder = EngineStart.findCacheFolder().resolve("sites");
            tellChanged(folder.resolve("sites.jars"), told);
            SiteCache.open(CacheStore.open(folder, "sites", SITES_CAP), stamp.build(),
                    folder.resolve("sites.distrust"));
        } catch (IOException | URISyntaxException | RuntimeException e) {
            LOGGER.debug("[Hotwire] call sites are made as classes load: {}", e.toString());
        }
    }

    private static void tellChanged(Path file, List<String> jars) throws IOException {
        List<String> before = Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
        if (before.equals(jars)) {
            return;
        }
        List<String> changed = new ArrayList<>(jars);
        changed.removeAll(before);
        if (!before.isEmpty()) {
            LOGGER.info("[Hotwire] call sites are made again for {} jars new or changed since the last start: {}",
                    changed.size(), changed.subList(0, Math.min(5, changed.size())));
        }
        Files.createDirectories(file.getParent());
        AtomicFiles.write(file, String.join("\n", jars).getBytes(StandardCharsets.UTF_8));
    }

    static void stop() {
        Instrumentation found = instrumentation;
        ClassFileTransformer watcher = early;
        if (found != null && watcher != null) {
            found.removeTransformer(watcher);
        }
        SiteCache.finish();
        early = null;
    }
}
