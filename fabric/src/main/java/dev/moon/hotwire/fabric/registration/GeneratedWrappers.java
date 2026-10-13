package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.ClassRebuilder;
import dev.moon.hotwire.redefine.RebuildException;
import dev.moon.hotwire.redefine.SeeThrough;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GeneratedWrappers {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeneratedWrappers.class);

    private final ClassRebuilder rebuilder;
    private final Function<String, List<String>> generatedBy;

    private final Map<String, Map<Class<?>, byte[]>> removed = new HashMap<>();

    public GeneratedWrappers(ClassRebuilder rebuilder, Function<String, List<String>> generatedBy) {
        this.rebuilder = rebuilder;
        this.generatedBy = generatedBy;
    }

    public synchronized void takeOut(Collection<String> mods) {
        Map<String, List<Class<?>>> typesOf = new LinkedHashMap<>();
        for (String mod : mods) {
            List<Class<?>> types = new ArrayList<>();
            for (String name : this.generatedBy.apply(mod)) {
                Class<?> type = findLoaded(name);
                if (type != null) {
                    types.add(type);
                }
            }
            if (!types.isEmpty() && !this.removed.containsKey(mod)) {
                typesOf.put(mod, types);
            }
        }
        if (typesOf.isEmpty()) {
            return;
        }
        try {
            this.seeThrough(typesOf);
        } catch (RebuildException | RuntimeException e) {
            typesOf.forEach((mod, types) -> {
                try {
                    this.seeThrough(Map.of(mod, types));
                } catch (RebuildException | RuntimeException again) {
                    LOGGER.warn("[Hotwire] the classes Mixin made from {}'s mixins keep calling it: {}", mod,
                            again.toString());
                }
            });
        }
    }

    private void seeThrough(Map<String, List<Class<?>>> typesOf) throws RebuildException {
        Map<String, byte[]> running = this.rebuilder.getLoadedBytes().readCurrent(
                typesOf.values().stream().flatMap(List::stream).toList());
        Map<Class<?>, byte[]> through = new HashMap<>();
        Map<String, Map<Class<?>, byte[]>> retainedOf = new LinkedHashMap<>();
        typesOf.forEach((mod, types) -> {
            for (Class<?> type : types) {
                byte[] bytes = running.get(Type.getInternalName(type));
                byte[] changed = bytes == null ? null : SeeThrough.of(bytes, face -> getMethodsOf(face, mod));
                if (changed != null) {
                    through.put(type, changed);
                    retainedOf.computeIfAbsent(mod, any -> new HashMap<>()).put(type, bytes);
                }
            }
        });
        if (through.isEmpty()) {
            return;
        }
        this.rebuilder.redefine(through);
        retainedOf.forEach((mod, retained) -> {
            this.removed.put(mod, retained);
            LOGGER.info("[Hotwire] {} classes Mixin made from {}'s mixins pass their calls straight to what they wrap"
                    + " while it is off: {}", retained.size(), mod, retained.keySet().stream().map(Class::getName)
                    .sorted().toList());
        });
    }

    public void readAhead(Collection<String> mods) {
        List<Class<?>> types = new ArrayList<>();
        for (String mod : mods) {
            for (String name : this.generatedBy.apply(mod)) {
                Class<?> type = findLoaded(name);
                if (type != null && this.rebuilder.getLoadedBytes().findKnown(type) == null) {
                    types.add(type);
                }
            }
        }
        try {
            this.rebuilder.getLoadedBytes().read(types, new ArrayList<>());
        } catch (RebuildException e) {
            LOGGER.debug("[Hotwire] the classes Mixin made from {}'s mixins are read at the off: {}", mods,
                    e.toString());
        }
    }

    public synchronized void putBack(Collection<String> mods) {
        Map<String, Map<Class<?>, byte[]>> backOf = new LinkedHashMap<>();
        mods.forEach(mod -> {
            Map<Class<?>, byte[]> back = this.removed.remove(mod);
            if (back != null) {
                backOf.put(mod, back);
            }
        });
        Map<Class<?>, byte[]> all = new HashMap<>();
        backOf.values().forEach(all::putAll);
        try {
            if (!all.isEmpty()) {
                this.rebuilder.redefine(all);
            }
        } catch (RebuildException together) {
            backOf.forEach((mod, back) -> {
                try {
                    this.rebuilder.redefine(back);
                } catch (RebuildException e) {
                    LOGGER.warn("[Hotwire] the classes Mixin made from {}'s mixins could not run their own code"
                            + " again", mod, e);
                }
            });
        }
    }

    private static Set<String> getMethodsOf(String face, String mod) {
        Class<?> type = findLoaded(face.replace('/', '.'));
        if (type == null || mod.equals(GameRegistrations.findModOf(type))) {
            return Set.of();
        }
        Set<String> methods = new HashSet<>();
        for (Method method : type.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) {
                methods.add(method.getName() + Type.getMethodDescriptor(method));
            }
        }
        return methods;
    }

    private static @Nullable Class<?> findLoaded(String name) {
        try {
            return Class.forName(name, false, GeneratedWrappers.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }
}
