package dev.moon.hotwire.fabric.rebuild;

import dev.moon.hotwire.concurrent.Parallel;
import dev.moon.hotwire.fabric.ModPolicy;
import dev.moon.hotwire.fabric.registration.ModOfClass;
import dev.moon.hotwire.jar.JarPaths;
import dev.moon.hotwire.jar.ModJar;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ForeignPatches {
    private static final Logger LOGGER = LoggerFactory.getLogger(ForeignPatches.class);

    private final Instrumentation instrumentation;
    private final TransformerChain chain;
    private final ConstantPools pools;
    private final ClassLoader game;

    private final Map<String, ModJar> jars;
    private final Set<String> family = new HashSet<>();

    private final Set<Path> familyJars = new HashSet<>();

    private final Map<String, Set<String>> rebuiltAtOff = new ConcurrentHashMap<>();
    private final Map<ProtectionDomain, Boolean> familyDomains = new ConcurrentHashMap<>();

    public ForeignPatches(Instrumentation instrumentation, TransformerChain chain, ConstantPools pools,
            ClassLoader game, List<ModJar> jars) {
        this.instrumentation = instrumentation;
        this.chain = chain;
        this.pools = pools;
        this.game = game;
        this.jars = indexById(jars);

        for (ModJar top : jars) {
            Set<String> ids = indexById(List.of(top)).keySet();
            if (!Collections.disjoint(ids, chain.getOwners())) {
                for (String id : ids) {
                    if (ModPolicy.isSwitchable(id)) {
                        this.family.add(id);
                    }
                }
            }
        }
        FabricLoader loader = FabricLoader.getInstance();
        for (ModContainer mod : loader.getAllMods()) {
            if (this.isInFamily(loader, mod)) {
                for (Path root : mod.getRootPaths()) {
                    Path jar = ModOfClass.findJarOf(root);
                    if (jar != null) {
                        this.familyJars.addAll(JarPaths.findKeysOf(jar));
                    }
                }
                if (mod.getOrigin().getKind() == ModOrigin.Kind.PATH) {
                    mod.getOrigin().getPaths().forEach(path -> this.familyJars.addAll(JarPaths.findKeysOf(path)));
                }
            }
        }
    }

    private boolean isInFamily(FabricLoader loader, ModContainer mod) {
        ModContainer at = mod;
        for (int depth = 0; depth < 8; depth++) {
            if (this.family.contains(at.getMetadata().getId())) {
                return true;
            }
            if (at.getOrigin().getKind() != ModOrigin.Kind.NESTED) {
                return false;
            }
            ModContainer parent = loader.getModContainer(at.getOrigin().getParentModId()).orElse(null);
            if (parent == null) {
                return false;
            }
            at = parent;
        }
        return false;
    }

    private boolean fromFamily(Class<?> type) {
        return this.familyDomains.computeIfAbsent(type.getProtectionDomain(), domain -> {
            try {
                CodeSource source = domain.getCodeSource();
                URL location = source == null ? null : source.getLocation();
                return location != null && "file".equals(location.getProtocol())
                        && this.familyJars.contains(Path.of(location.toURI()).toAbsolutePath().normalize());
            } catch (URISyntaxException | RuntimeException e) {
                return false;
            }
        });
    }

    private static Map<String, ModJar> indexById(List<ModJar> jars) {
        Map<String, ModJar> byId = new HashMap<>();
        for (ModJar jar : jars) {
            byId.put(jar.manifest().id(), jar);
            byId.putAll(indexById(jar.nested()));
        }
        return byId;
    }

    public static Function<Class<?>, @Nullable String> makeOwnerFinder(List<ModJar> jars) {
        Map<String, ModJar> byId = indexById(jars);
        return type -> {
            String name = type.getName().replace('.', '/');
            for (ModJar jar : byId.values()) {
                if (jar.patches().owns().contains(name)) {
                    return jar.manifest().id();
                }
            }
            return null;
        };
    }

    @SuppressWarnings("ReferenceEquality")
    public Set<String> findClassesFor(Set<String> switched, Set<String> on) {
        if (Collections.disjoint(switched, this.family)) {
            return Set.of();
        }
        Set<String> classes = new HashSet<>();
        if (on.containsAll(switched)) {
            switched.forEach(mod -> classes.addAll(this.rebuiltAtOff.getOrDefault(mod, Set.of())));
            this.chain.getLoadedWhileOff().forEach((name, mixed) -> {
                if (!Arrays.equals(this.chain.apply(name, mixed, on::contains), mixed)) {
                    classes.add(name.replace('.', '/'));
                }
            });
            return classes;
        }
        long start = System.nanoTime();
        Set<String> named = new HashSet<>();
        switched.forEach(mod -> {
            ModJar jar = this.jars.get(mod);
            if (jar != null) {
                named.addAll(jar.patches().owns());
            }
        });

        Set<String> off = new HashSet<>();
        for (ModJar jar : this.jars.values()) {
            if (!on.contains(jar.manifest().id())) {
                off.addAll(jar.patches().owns());
            }
        }
        List<Class<?>> read = new ArrayList<>();
        List<Class<?>> others = new ArrayList<>();
        for (Class<?> type : this.instrumentation.getAllLoadedClasses()) {
            if (type.getClassLoader() != this.game || type.isHidden()) {
                continue;
            }
            boolean ours = !this.fromFamily(type) && !off.contains(type.getName().replace('.', '/'));
            (ours ? read : others).add(type);
        }
        for (Class<?> type : Parallel.filter(read, type -> this.pools.names(type, named::contains))) {
            classes.add(type.getName().replace('.', '/'));
        }

        List<String> leftOut = new ArrayList<>();
        for (Class<?> type : Parallel.filter(others, type -> this.pools.names(type, named::contains))) {
            if (leftOut.size() < 12) {
                leftOut.add(type.getName() + (this.fromFamily(type) ? " (family)" : " (off)"));
            }
        }
        if (!leftOut.isEmpty()) {
            LOGGER.info("[Hotwire] left out as the switched mods' own: {}", leftOut);
        }
        switched.forEach(mod -> this.rebuiltAtOff.put(mod, Set.copyOf(classes)));
        LOGGER.info("[Hotwire] {} of {} classes call {} outside Mixin and are rebuilt with them ({} ms): {}",
                classes.size(), read.size(), switched, (System.nanoTime() - start) / 1_000_000,
                new TreeSet<>(classes));
        return classes;
    }
}
