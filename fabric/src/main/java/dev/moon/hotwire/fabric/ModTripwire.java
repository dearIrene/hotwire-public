package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.redefine.ClassRebuilder;
import dev.moon.hotwire.redefine.HotwireLoaders;
import dev.moon.hotwire.redefine.LambdaSites;
import dev.moon.hotwire.redefine.RebuildException;
import dev.moon.hotwire.redefine.Tripwire;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModTripwire {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModTripwire.class);

    public record Observation(Tripwire.Hits hits, List<String> loadedMeanwhile, List<String> firstLoad,
            List<String> onItsWay) {
        public boolean hasRun() {
            return this.hits.calls() > 0 || !this.loadedMeanwhile.isEmpty();
        }
    }

    private static final class ArmedClass {
        private final String mod;
        private final byte[] before;

        private final int wired;

        ArmedClass(String mod, byte[] before, byte[] wired) {
            this.mod = mod;
            this.before = before;
            this.wired = Arrays.hashCode(wired);
        }

        boolean isAsWired(byte @Nullable [] known) {
            return known == null || Arrays.hashCode(known) == this.wired;
        }

        String getMod() {
            return this.mod;
        }

        byte[] getBefore() {
            return this.before;
        }
    }

    private final Instrumentation instrumentation;
    private final ClassRebuilder rebuilder;

    private final Function<String, @Nullable Class<?>> loadedClassOf;
    private final Map<Class<?>, ArmedClass> armed = new HashMap<>();

    private final Map<String, ArmedClass> armedAtLoad = new HashMap<>();

    private final Map<String, String> sources = new HashMap<>();
    private final Set<Class<?>> loadedAtArm = new HashSet<>();

    private final Map<String, String> loadedWhileArming = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> ARMING = ThreadLocal.withInitial(() -> false);
    private final ClassFileTransformer loading = new ClassFileTransformer() {
        @Override
        public byte @Nullable [] transform(@Nullable ClassLoader loader, @Nullable String name,
                @Nullable Class<?> redefining, @Nullable ProtectionDomain domain, byte[] bytes) {
            if (name == null || redefining != null || !HotwireLoaders.seesHotwire(loader)) {
                return null;
            }
            try {
                return ModTripwire.this.armAtLoad(name, findSourceOf(domain), bytes);
            } catch (RuntimeException | LinkageError e) {
                LOGGER.error("[Hotwire] {} loaded unwatched: wiring it as it loaded threw", name, e);
                return null;
            }
        }
    };

    private final ExecutorService watcher = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("hotwire-watch").daemon().factory());

    ModTripwire(Instrumentation instrumentation, ClassRebuilder rebuilder,
            Function<String, @Nullable Class<?>> loadedClassOf) {
        this.instrumentation = instrumentation;
        this.rebuilder = rebuilder;
        this.loadedClassOf = loadedClassOf;
    }

    public synchronized int arm(Collection<String> ids) throws RebuildException {
        ids.forEach(Tripwire::watchIfNew);
        return this.wire(ids, Set.of());
    }

    void armEarly(Collection<String> ids, Set<String> rebuilt) {
        this.watcher.execute(() -> {
            synchronized (this) {
                try {
                    this.wire(ids, rebuilt);
                } catch (RebuildException | RuntimeException e) {
                    LOGGER.warn("[Hotwire] {} could not be wired ahead of its off: {}", ids, e.toString());
                }
            }
        });
    }

    void readAhead(Collection<String> ids) {
        List<Class<?>> types = new ArrayList<>();
        this.findOwned(ids).keySet().forEach(type -> {
            if (!type.isHidden() && this.instrumentation.isModifiableClass(type)
                    && this.rebuilder.getLoadedBytes().findKnown(type) == null) {
                types.add(type);
            }
        });
        try {
            this.rebuilder.getLoadedBytes().read(types, new ArrayList<>());
        } catch (RebuildException e) {
            LOGGER.debug("[Hotwire] {}'s classes are read at the off: {}", ids, e.toString());
        }
    }

    void unwire(Collection<String> ids) {
        this.watcher.execute(() -> {
            try {
                this.disarm(ids);
            } catch (RebuildException | RuntimeException e) {
                LOGGER.warn("[Hotwire] {} stays wired after an off that did not happen: {}", ids, e.toString());
            }
        });
    }

    private Set<Class<?>> redefineEach(Map<Class<?>, byte[]> batch, List<String> refused) {
        if (batch.isEmpty()) {
            return Set.of();
        }
        try {
            this.rebuilder.redefine(batch);
            return batch.keySet();
        } catch (RebuildException e) {
            if (batch.size() == 1) {
                refused.add(batch.keySet().iterator().next().getName() + " (" + e.getMessage() + ")");
                return Set.of();
            }
            List<Class<?>> types = new ArrayList<>(batch.keySet());
            Map<Class<?>, byte[]> first = new LinkedHashMap<>();
            Map<Class<?>, byte[]> second = new LinkedHashMap<>();
            for (int index = 0; index < types.size(); index++) {
                (index < types.size() / 2 ? first : second).put(types.get(index), batch.get(types.get(index)));
            }
            Set<Class<?>> took = new HashSet<>(this.redefineEach(first, refused));
            took.addAll(this.redefineEach(second, refused));
            return took;
        }
    }

    private int wire(Collection<String> ids, Set<String> rebuilt) throws RebuildException {
        Map<Class<?>, String> owned = new LinkedHashMap<>();
        this.findOwned(ids).forEach((type, mod) -> {
            if (rebuilt.contains(type.getName().replace('.', '/'))) {
                return;
            }
            this.loadedAtArm.add(type);
            String source = findSourceOf(type.getProtectionDomain());
            if (source != null) {
                synchronized (this.sources) {
                    this.sources.put(source, mod);
                }
            }
            if (!type.isHidden() && this.instrumentation.isModifiableClass(type) && !this.armed.containsKey(type)) {
                owned.put(type, mod);
            }
        });
        this.instrumentation.removeTransformer(this.loading);
        this.instrumentation.addTransformer(this.loading);

        ARMING.set(true);
        try {
            Map<String, byte[]> current = this.readBytes(List.copyOf(owned.keySet()));
            Map<Class<?>, byte[]> wiredAll = new LinkedHashMap<>();
            Map<Class<?>, ArmedClass> arming = new HashMap<>();
            owned.forEach((type, mod) -> {
                byte[] bytes = current.get(type.getName().replace('.', '/'));
                byte[] wired = bytes == null ? null : Tripwire.arm(bytes, mod);
                if (bytes != null && wired != null) {
                    arming.put(type, new ArmedClass(mod, bytes, wired));
                    wiredAll.put(type, wired);
                }
            });

            List<String> refused = new ArrayList<>();
            Set<Class<?>> took = this.redefineEach(wiredAll, refused);
            took.forEach(type -> this.armed.put(type, arming.get(type)));

            List<String> unusable = new ArrayList<>();
            arming.forEach((type, one) -> {
                if (!took.contains(type) && this.redefineEach(Map.of(type, one.before), new ArrayList<>())
                        .isEmpty()) {
                    unusable.add(type.getName());
                }
            });
            refused.removeIf(line -> unusable.contains(line.substring(0, line.indexOf(" ("))));
            if (!unusable.isEmpty()) {
                LOGGER.info("[Hotwire] {} classes of {} are not watched: the JVM takes them in no form, wired or as"
                        + " they run: {}", unusable.size(), ids, unusable);
            }
            if (!refused.isEmpty()) {
                LOGGER.warn("[Hotwire] {} classes of {} could not be wired, and are not watched: {}", refused.size(),
                        ids, refused);
            }
            return took.size();
        } finally {
            ARMING.remove();
        }
    }

    void armLater(Collection<String> ids, Predicate<String> stillOff, Consumer<List<String>> whenArmed) {
        this.watcher.execute(() -> {
            synchronized (this) {
                List<String> off = ids.stream().filter(stillOff).toList();
                if (off.isEmpty()) {
                    return;
                }
                try {
                    long began = System.nanoTime();
                    int armed = this.arm(off);
                    LOGGER.info("[Hotwire] {} classes of {} are watched while off ({} ms)", armed, off,
                            (System.nanoTime() - began) / 1_000_000);
                    whenArmed.accept(off);
                } catch (RebuildException | RuntimeException e) {
                    LOGGER.warn("[Hotwire] {} could not be watched while off", off, e);
                }
            }
        });
    }

    public synchronized Map<String, Observation> getObservations(Collection<String> ids) {
        Map<String, List<String>> loaded = this.findLoadedSinceArm(ids);
        Map<String, Observation> seen = new TreeMap<>();
        ids.forEach(id -> {
            List<String> reached = new ArrayList<>();
            List<String> onItsWay = new ArrayList<>();
            for (String name : loaded.getOrDefault(id, List.of())) {
                String internal = name.replace('.', '/');
                int hidden = internal.indexOf("/0x");
                internal = hidden < 0 ? internal : internal.substring(0, hidden);
                Tripwire.Hits hits = Tripwire.peek(id);
                boolean hiddenLambda = hidden >= 0 && !Tripwire.hasSeenLoad(id, internal) && hits.calls() == 0
                        && hits.waiting() > 0;
                (hiddenLambda || Tripwire.isLoadedOnItsWay(id, internal) ? onItsWay : reached).add(name);
            }
            seen.put(id, new Observation(Tripwire.peek(id), reached,
                    reached.isEmpty() ? List.of() : Tripwire.getFirstLoad(id), onItsWay));
        });
        return seen;
    }

    public void awaitIdle() throws InterruptedException {
        try {
            this.watcher.submit(() -> { }).get(1, TimeUnit.MINUTES);
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("[Hotwire] the tripwire's own work did not finish: {}", e.toString());
        }
    }

    public synchronized Map<String, Observation> disarm(Collection<String> ids) throws RebuildException {
        Map<String, Observation> seen = this.getObservations(ids);
        this.disarmClasses(ids);
        return seen;
    }

    synchronized void disarmQuietly(Collection<String> ids) throws RebuildException {
        this.disarmClasses(ids);
    }

    private void disarmClasses(Collection<String> ids) throws RebuildException {
        ids.forEach(Tripwire::unwatch);
        Map<Class<?>, byte[]> back = new HashMap<>();
        this.armed.entrySet().removeIf(entry -> {
            if (ids.contains(entry.getValue().getMod())) {
                this.putBack(entry.getKey(), entry.getValue(), back);
                return true;
            }
            return false;
        });

        Map<String, ArmedClass> atLoad = new HashMap<>();
        synchronized (this.sources) {
            this.armedAtLoad.entrySet().removeIf(entry -> {
                if (!ids.contains(entry.getValue().getMod())) {
                    return false;
                }
                atLoad.put(entry.getKey(), entry.getValue());
                return true;
            });
            this.sources.values().removeIf(ids::contains);
            if (this.sources.isEmpty()) {
                this.instrumentation.removeTransformer(this.loading);
            }
        }
        atLoad.forEach((name, was) -> {
            Class<?> type = this.loadedClassOf.apply(name);
            if (type != null) {
                this.putBack(type, was, back);
            }
        });
        this.loadedAtArm.removeIf(type -> {
            String mod = GameRegistrations.findModOf(type);
            return mod != null && ids.contains(mod);
        });
        this.loadedWhileArming.values().removeIf(ids::contains);

        this.watcher.execute(() -> {
            synchronized (this) {
                try {
                    this.rebuilder.redefine(back);
                } catch (RebuildException | RuntimeException e) {
                    LOGGER.warn("[Hotwire] {} keep their probes, which only return while unwatched: {}", ids,
                            e.toString());
                }
            }
        });
    }

    private void putBack(Class<?> type, ArmedClass was, Map<Class<?>, byte[]> back) {
        if (was.isAsWired(this.rebuilder.getLoadedBytes().findKnown(type))) {
            back.put(type, LambdaSites.rewrite(was.getBefore()));
        } else {
            LOGGER.info("[Hotwire] {} was rebuilt while watched and stays as rebuilt", type.getName());
        }
    }

    synchronized Set<String> getArmedMods() {
        Set<String> mods = new HashSet<>();
        this.armed.values().forEach(armed -> mods.add(armed.getMod()));
        return mods;
    }

    private Map<String, List<String>> findLoadedSinceArm(Collection<String> ids) {
        Map<String, List<String>> loaded = new TreeMap<>();
        this.findOwned(ids).forEach((type, mod) -> {
            if (!this.loadedAtArm.contains(type) && !this.loadedWhileArming.containsKey(type.getName())) {
                loaded.computeIfAbsent(mod, any -> new ArrayList<>()).add(type.getName());
            }
        });
        return loaded;
    }

    private static @Nullable String findSourceOf(@Nullable ProtectionDomain domain) {
        CodeSource source = domain == null ? null : domain.getCodeSource();
        return source == null || source.getLocation() == null ? null : source.getLocation().toString();
    }

    private byte @Nullable [] armAtLoad(String name, @Nullable String source, byte[] bytes) {
        synchronized (this.sources) {
            String mod = source == null ? null : this.sources.get(source);
            if (mod == null) {
                return null;
            }
            if (ARMING.get()) {
                this.loadedWhileArming.put(name.replace('/', '.'), mod);
            } else {
                Tripwire.onLoading(mod, name);
            }
            byte[] wired = Tripwire.arm(bytes, mod);
            if (wired != null) {
                this.armedAtLoad.put(name, new ArmedClass(mod, bytes, wired));
            }
            return wired;
        }
    }

    private Map<String, byte[]> readBytes(List<Class<?>> types) {
        List<String> refused = new ArrayList<>();
        try {
            Map<String, byte[]> read = this.rebuilder.getLoadedBytes().read(types, refused);
            if (!refused.isEmpty()) {
                LOGGER.info("[Hotwire] {} classes are left unarmed, as the JVM cannot give back their bytes: {}",
                        refused.size(), refused);
            }
            return read;
        } catch (RebuildException e) {
            LOGGER.info("[Hotwire] {} classes are left unarmed: {}", types.size(), e.getMessage());
            return Map.of();
        }
    }

    private Map<Class<?>, String> findOwned(Collection<String> ids) {
        Map<Class<?>, String> owned = new LinkedHashMap<>();
        for (Class<?> type : this.instrumentation.getAllLoadedClasses()) {
            if (type.isArray() || type.isPrimitive() || !HotwireLoaders.seesHotwire(type.getClassLoader())) {
                continue;
            }
            String mod = GameRegistrations.findModOf(type);
            if (mod != null && ids.contains(mod)) {
                owned.put(type, mod);
            }
        }
        return owned;
    }
}
