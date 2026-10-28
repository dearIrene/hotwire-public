package dev.moon.hotwire.switching;

import dev.moon.hotwire.redefine.AccessBridge;
import dev.moon.hotwire.redefine.Adoption;
import dev.moon.hotwire.redefine.DuckBridge;
import dev.moon.hotwire.redefine.HotwireLoaders;
import dev.moon.hotwire.redefine.Linker;
import dev.moon.hotwire.redefine.OldClasses;
import dev.moon.hotwire.redefine.ReflectionBridge;
import dev.moon.hotwire.redefine.Relocations;
import dev.moon.hotwire.redefine.ShutOverrides;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LoadTimeBridge implements ClassFileTransformer {
    private static final Logger LOGGER = LoggerFactory.getLogger(LoadTimeBridge.class);

    private final Predicate<String> differs;
    private final BiFunction<String, byte[], byte @Nullable []> mix;
    private final DefinitionWatcher defined;

    interface DefinitionWatcher {
        void accept(String name, byte[] bytes, @Nullable ClassLoader inGame);
    }

    private static final ThreadLocal<Set<String>> MIXING = ThreadLocal.withInitial(HashSet::new);

    LoadTimeBridge(Predicate<String> differs, BiFunction<String, byte[], byte @Nullable []> mix,
            DefinitionWatcher defined) {
        this.differs = differs;
        this.mix = mix;
        this.defined = defined;
    }

    @Override
    public byte @Nullable [] transform(@Nullable ClassLoader loader, @Nullable String className,
            @Nullable Class<?> redefining, @Nullable ProtectionDomain domain, byte[] bytes) {
        if (className == null || redefining != null) {
            return null;
        }
        if (!MIXING.get().add(className)) {
            LOGGER.error("[Hotwire] {} was asked for while it was being mixed", className,
                    new IllegalStateException(className));
            return null;
        }
        try {
            byte[] made = this.bridge(loader, className, domain, bytes);
            this.defined.accept(className, made == null ? bytes : made,
                    loader != null && HotwireLoaders.seesHotwire(loader) ? loader : null);
            return made;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("[Hotwire] {} loaded as the game made it, with the game's own mix and no bridge to what"
                    + " switches added", className, e);
            return null;
        } finally {
            MIXING.get().remove(className);
        }
    }

    @SuppressWarnings("ReferenceEquality")
    private byte @Nullable [] bridge(@Nullable ClassLoader loader, String className, @Nullable ProtectionDomain domain,
            byte[] bytes) {
        byte[] mixed = this.differs.test(className) ? this.mix.apply(className, bytes) : null;

        byte[] base = Relocations.remap(mixed == null ? bytes : mixed);
        if (base != bytes) {
            mixed = base;
        }
        if (!HotwireLoaders.seesHotwire(loader) || isHotwireItself(domain) || AccessBridge.isAccessor(className)) {
            return mixed;
        }

        byte[] bridged = Adoption.rewrite(AccessBridge.eraseCallSites(ReflectionBridge.rewrite(DuckBridge.rewrite(
                AccessBridge.rewrite(ShutOverrides.renameOverrides(base), Linker.getOpenings()),
                Linker.getEverDucks())), Linker.getOpenings()), Linker.getAdopted());
        if (bridged != base) {
            bridged = OldClasses.raiseIfNeeded(bridged, loader);
        }
        dumpLoad(className, bytes, mixed, bridged);
        return bridged == base ? mixed : bridged;
    }

    private static boolean isHotwireItself(@Nullable ProtectionDomain domain) {
        CodeSource own = Linker.class.getProtectionDomain().getCodeSource();
        CodeSource source = domain == null ? null : domain.getCodeSource();
        return own != null && source != null && Objects.equals(own.getLocation(), source.getLocation());
    }

    private static void dumpLoad(String className, byte[] given, byte @Nullable [] mixed, byte[] returned) {
        String folder = System.getProperty("hotwire.dump");
        String part = System.getProperty("hotwire.dumpLoad");
        if (folder == null || part == null || !className.contains(part)) {
            return;
        }
        try {
            Path base = Path.of(folder).resolve(className.replace('/', '.') + ".load");
            Files.createDirectories(base);
            Files.write(base.resolve("given.class"), given);
            if (mixed != null) {
                Files.write(base.resolve("mixed.class"), mixed);
            }
            Files.write(base.resolve("returned.class"), returned);
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] could not write the load-time bytes of {}", className, e);
        }
    }
}
