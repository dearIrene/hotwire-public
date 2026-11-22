package dev.moon.hotwire.fabric.add;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OpenLibraryRegistries implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenLibraryRegistries.class);
    private static final String FROZEN = "frozen";

    private static final Set<String> NOT_LIBRARIES = Set.of("minecraft", "fabricloader", "java", "fabric-api",
            "fabric");

    private static volatile Predicate<Class<?>> initialized = type -> false;

    private final IdentityHashMap<Object, Field> opened = new IdentityHashMap<>();

    private OpenLibraryRegistries() {
    }

    public static void setInitialized(Predicate<Class<?>> check) {
        initialized = check;
    }

    static OpenLibraryRegistries open(Collection<String> ids, Function<String, @Nullable Class<?>> findLoaded) {
        OpenLibraryRegistries open = new OpenLibraryRegistries();
        for (String library : findLibraries(ids)) {
            FabricLoader.getInstance().getModContainer(library).ifPresent(mod -> open.openIn(mod, findLoaded));
        }
        if (!open.opened.isEmpty()) {
            LOGGER.info("[Hotwire] {} registries of the libraries {} needs are opened for its start",
                    open.opened.size(), ids);
        }
        return open;
    }

    private static Set<String> findLibraries(Collection<String> ids) {
        Set<String> libraries = new LinkedHashSet<>();
        for (String id : ids) {
            FabricLoader.getInstance().getModContainer(id).ifPresent(mod -> {
                for (ModDependency dependency : mod.getMetadata().getDependencies()) {
                    String needed = dependency.getModId();
                    if (dependency.getKind() == ModDependency.Kind.DEPENDS && !NOT_LIBRARIES.contains(needed)
                            && !needed.startsWith("fabric-") && !ids.contains(needed)) {
                        libraries.add(needed);
                    }
                }
            });
        }
        return libraries;
    }

    private void openIn(ModContainer mod, Function<String, @Nullable Class<?>> findLoaded) {
        for (Path root : mod.getRootPaths()) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(file -> file.toString().endsWith(".class")).forEach(file -> {
                    String name = root.relativize(file).toString().replace('\\', '/');
                    Class<?> loaded = findLoaded.apply(name.substring(0, name.length() - ".class".length()));
                    if (loaded != null && initialized.test(loaded)) {
                        this.openStatics(loaded);
                    }
                });
            } catch (IOException | RuntimeException e) {
                LOGGER.debug("[Hotwire] the classes of {} could not be listed", mod.getMetadata().getId(), e);
            }
        }
    }

    private void openStatics(Class<?> type) {
        Field[] fields;
        try {
            fields = type.getDeclaredFields();
        } catch (LinkageError e) {
            return;
        }
        for (Field field : fields) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()
                    || !field.trySetAccessible()) {
                continue;
            }
            try {
                Object registry = field.get(null);
                Field frozen = registry == null || this.opened.containsKey(registry) ? null
                        : findFrozen(registry.getClass());
                if (frozen != null && frozen.getBoolean(registry)) {
                    frozen.setBoolean(registry, false);
                    this.opened.put(registry, frozen);
                }
            } catch (IllegalAccessException | RuntimeException | LinkageError e) {
            }
        }
    }

    private static @Nullable Field findFrozen(Class<?> type) {
        try {
            Field frozen = type.getDeclaredField(FROZEN);
            Method freeze = type.getDeclaredMethod("freeze");
            if (frozen.getType() != boolean.class || Modifier.isStatic(frozen.getModifiers())
                    || freeze.getParameterCount() != 0 || !frozen.trySetAccessible()) {
                return null;
            }
            return frozen;
        } catch (NoSuchFieldException | NoSuchMethodException | LinkageError e) {
            return null;
        }
    }

    @Override
    public void close() {
        List<String> failed = new ArrayList<>();
        this.opened.forEach((registry, frozen) -> {
            try {
                frozen.setBoolean(registry, true);
            } catch (IllegalAccessException | RuntimeException e) {
                failed.add(registry.getClass().getName());
            }
        });
        if (!failed.isEmpty()) {
            LOGGER.warn("[Hotwire] registries of libraries could not be frozen again: {}", failed);
        }
        this.opened.clear();
    }
}
