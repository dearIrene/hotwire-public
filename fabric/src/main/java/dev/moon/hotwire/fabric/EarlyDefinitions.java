package dev.moon.hotwire.fabric;

import dev.moon.hotwire.jvm.JvmAccess;
import dev.moon.hotwire.jvm.JvmAccessException;
import dev.moon.hotwire.redefine.HandlerGuards;
import dev.moon.hotwire.redefine.LoadedClassBytes;
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
    @SuppressWarnings("ReferenceEquality")
    public void onPreLaunch() {
        ClassLoader game = EarlyDefinitions.class.getClassLoader();
        try {
            Path jar = FabricLoader.getInstance().getModContainer("hotwire")
                    .flatMap(mod -> mod.getOrigin().getPaths().stream().findFirst()).orElse(null);
            if (jar == null) {
                return;
            }
            Instrumentation found = JvmAccess.getInstrumentation(jar);
            ClassFileTransformer watcher = new ClassFileTransformer() {
                @Override
                public byte @Nullable [] transform(@Nullable ClassLoader loader, @Nullable String name,
                        @Nullable Class<?> redefining, @Nullable ProtectionDomain domain, byte[] bytes) {
                    if (name == null || redefining != null || loader != game) {
                        return null;
                    }

                    byte[] made = HandlerGuards.ENABLED ? HandlerGuards.rewrite(name, bytes) : null;
                    LoadedClassBytes.recordDefinedInGame(name, made == null ? bytes : made, loader);
                    return made;
                }
            };
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
