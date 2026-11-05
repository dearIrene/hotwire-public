package dev.moon.hotwire.fabric;

import dev.moon.hotwire.config.HotwireSettings;
import dev.moon.hotwire.fabric.report.HotwireLog;
import dev.moon.hotwire.jvm.JvmAccess;
import dev.moon.hotwire.jvm.JvmAccessException;
import dev.moon.hotwire.redefine.HandlerGuards;
import dev.moon.hotwire.redefine.LoadedClassBytes;
import dev.moon.hotwire.redefine.OpenGameClasses;
import dev.moon.hotwire.report.StartTimes;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EarlyDefinitions implements PreLaunchEntrypoint {
    private static final Logger LOGGER = LoggerFactory.getLogger(EarlyDefinitions.class);
    private static volatile @Nullable Instrumentation instrumentation;
    private static volatile @Nullable ClassFileTransformer early;

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
                try (InputStream in = game.getResourceAsStream(name + ".class")) {
                    return in == null ? null : in.readAllBytes();
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
                    byte[] made = HandlerGuards.ENABLED ? HandlerGuards.rewrite(name, bytes) : null;
                    long rewritten = System.nanoTime();
                    byte[] recorded = made == null ? bytes : made;
                    LoadedClassBytes.recordDefinedInGame(name, recorded, loader);
                    StartTimes.add("early watcher: call sites", began, rewritten);
                    StartTimes.add("early watcher: keep the bytes", rewritten);
                    StartTimes.addBytes("early watcher: keep the bytes", recorded.length);
                    return made;
                }
            };

            found.addTransformer(new OpenGameClasses(game));
            found.addTransformer(watcher);
            instrumentation = found;
            early = watcher;
        } catch (JvmAccessException | RuntimeException | LinkageError e) {
            LOGGER.debug("[Hotwire] classes are not kept from the start: {}", e.toString());
        }
    }

    static void stop() {
        Instrumentation found = instrumentation;
        ClassFileTransformer watcher = early;
        if (found != null && watcher != null) {
            found.removeTransformer(watcher);
        }
        early = null;
    }
}
