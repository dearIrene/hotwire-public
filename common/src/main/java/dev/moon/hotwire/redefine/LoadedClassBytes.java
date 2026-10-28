package dev.moon.hotwire.redefine;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LoadedClassBytes {
    private static final Logger LOGGER = LoggerFactory.getLogger(LoadedClassBytes.class);

    private final Instrumentation instrumentation;

    private final Map<Class<?>, byte[]> knownBytes = Collections.synchronizedMap(new WeakHashMap<>());

    private final Map<Class<?>, String> unreadable = Collections.synchronizedMap(new WeakHashMap<>());

    private final Set<String> loadedAs = ConcurrentHashMap.newKeySet();

    private static final DefinedClasses DEFINED = new DefinedClasses();
    private final DefinedClasses definedAs = DEFINED;

    private static final Map<String, Integer> IN_GAME = new ConcurrentHashMap<>();
    private final Map<String, Integer> definedInGame = IN_GAME;

    LoadedClassBytes(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    public void onLoaded(String internal, byte[] bytes) {
        this.definedAs.put(internal, bytes);
        this.loadedAs.add(internal);
    }

    public void onDefined(String internal, byte[] bytes) {
        this.definedAs.put(internal, bytes);
        this.definedInGame.remove(internal);
    }

    public static void recordDefinedInGame(String internal, byte[] bytes, ClassLoader loader) {
        DEFINED.put(internal, bytes);
        IN_GAME.put(internal, System.identityHashCode(loader));
    }

    public void onDefinedInGame(String internal, byte[] bytes, ClassLoader loader) {
        recordDefinedInGame(internal, bytes, loader);
    }

    public byte @Nullable [] findAsLoaded(String internal) {
        return this.loadedAs.contains(internal) ? this.definedAs.find(internal) : null;
    }

    void remember(Class<?> type, byte[] bytes) {
        this.knownBytes.put(type, bytes);
    }

    void rememberAll(Map<Class<?>, byte[]> classes) {
        this.knownBytes.putAll(classes);
    }

    public byte @Nullable [] findKnown(Class<?> type) {
        return this.knownBytes.get(type);
    }

    public Map<String, byte[]> readCurrent(Collection<Class<?>> types) throws RebuildException {
        return this.read(types, null);
    }

    public Map<String, byte[]> read(Collection<Class<?>> types, @Nullable List<String> refusedOut)
            throws RebuildException {
        Map<String, byte[]> current = new ConcurrentHashMap<>();
        Map<String, Class<?>> byName = new LinkedHashMap<>();
        Map<String, String> refused = new LinkedHashMap<>();
        for (Class<?> type : types) {
            String name = type.getName().replace('.', '/');
            byte[] known = this.knownBytes.get(type);
            if (known == null) {
                boolean asLoaded = this.loadedAs.remove(name);
                Integer loader = this.definedInGame.remove(name);
                boolean inGame = loader != null && loader == System.identityHashCode(type.getClassLoader());
                known = asLoaded || inGame ? this.definedAs.find(name) : null;
                if (known != null) {
                    this.knownBytes.putIfAbsent(type, known);
                }
            }
            String why = this.unreadable.get(type);
            if (why != null) {
                refused.put(name, why);
            } else if (known == null) {
                byName.put(name, type);
            } else {
                current.put(name, known);
            }
        }
        long start = System.nanoTime();
        if (!byName.isEmpty()) {
            this.capture(byName, current, refused);
        }
        this.fillRefused(byName, current, refused);
        refused.forEach((name, why) -> {
            Class<?> type = byName.get(name);
            if (type != null) {
                this.unreadable.put(type, why);
            }
        });
        List<String> named = new ArrayList<>();
        refused.forEach((name, why) -> named.add(name.replace('/', '.') + " (" + why + ")"));
        if (refusedOut != null) {
            refusedOut.addAll(named);
        } else if (!named.isEmpty()) {
            throw new RebuildException("the JVM could not give back the bytes of " + named,
                    new IllegalStateException(named.toString()));
        }
        long end = System.nanoTime();

        for (String name : byName.keySet()) {
            byte[] bytes = current.get(name);
            if (bytes != null) {
                this.knownBytes.putIfAbsent(byName.get(name), bytes);
            }
        }
        LOGGER.debug("[Hotwire] Read {} loaded classes in {} ms", byName.size(), (end - start) / 1_000_000);
        return current;
    }

    private void fillRefused(Map<String, Class<?>> byName, Map<String, byte[]> current,
            Map<String, String> refused) {
        List<String> served = new ArrayList<>();
        for (String name : List.copyOf(refused.keySet())) {
            byte[] bytes = byName.containsKey(name) ? this.definedAs.find(name) : null;
            if (bytes != null) {
                current.put(name, bytes);
                refused.remove(name);
                served.add(name);
            }
        }
        if (!served.isEmpty()) {
            LOGGER.info("[Hotwire] the JVM would not give back {} classes; the bytes they were defined with stand"
                    + " in: {}", served.size(), served.subList(0, Math.min(8, served.size())));
        }
    }

    void capture(Map<String, Class<?>> byName, Map<String, byte[]> current, Map<String, String> refused)
            throws RebuildException {
        RetransformReader.capture(this.instrumentation, byName, current, refused);
        for (String name : byName.keySet()) {
            if (!current.containsKey(name)) {
                refused.putIfAbsent(name, "the JVM gave no bytes for it");
            }
        }
    }
}
