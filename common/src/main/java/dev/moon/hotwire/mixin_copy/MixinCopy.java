package dev.moon.hotwire.mixin_copy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MixinCopy implements AutoCloseable {
    public record Decorations(Map<String, String> modIds, Map<String, Integer> levels, Map<String, Integer> orders) {
        public static final Decorations NONE = new Decorations(Map.of(), Map.of(), Map.of());

        public Decorations {
            modIds = Map.copyOf(modIds);
            levels = Map.copyOf(levels);
            orders = Map.copyOf(orders);
        }
    }

    private record Copy(URLClassLoader loader, Function<Object[], Object[]> mixer, Runnable reset) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(MixinCopy.class);
    private static final String SERVICE = "mixin.service";
    private static final String BOOTSTRAP = "mixin.bootstrapService";
    private static final String COPY = MixinCopy.class.getPackageName() + ".copy.";
    private static final Object BOOTING = new Object();

    private static final ConcurrentLinkedDeque<Span> SPANS = new ConcurrentLinkedDeque<>();
    private static final int SPAN_COUNT = 8;
    private static volatile @Nullable Span mixing;

    private record Span(String thread, long from, long to) {
        boolean holds(String name, long millis) {
            return this.thread.equals(name) && millis >= this.from && millis <= this.to;
        }
    }
    private static @Nullable Path copyJar;

    private final List<URL> mixinJars;
    private final ClassLoader live;
    private final List<String> sharedPackages;
    private final String sessionId;
    private final String side;
    private volatile Decorations decorations;

    private volatile @Nullable String obfuscationContext;

    private volatile @Nullable String compatibility;
    private final Function<String, byte @Nullable []> more;
    private volatile ExtensionFolder folding = ExtensionFolder.NONE;

    private volatile Function<String, @Nullable Object> livePlugins = mixinPackage -> null;
    private volatile BiFunction<String, String, @Nullable Object> liveMixins = (target, mixin) -> null;
    private final Map<String, Optional<byte[]>> read = new ConcurrentHashMap<>();

    private final Map<String, Integer> missing = new ConcurrentHashMap<>();
    private static final AtomicInteger PATHS = new AtomicInteger();

    private @Nullable Copy reusable;

    public MixinCopy(List<Path> mixinJars, ClassLoader live, List<String> sharedPackages, String sessionId,
            Function<String, byte @Nullable []> more, String side, Decorations decorations) {
        this.side = side;
        this.decorations = decorations;
        this.more = name -> this.findBytes(name, more);
        this.mixinJars = mixinJars.stream().map(MixinCopy::toUrl).toList();
        this.live = live;
        this.sharedPackages = List.copyOf(sharedPackages);
        this.sessionId = sessionId;
    }

    public static void markClassPathChanged() {
        PATHS.incrementAndGet();
    }

    private byte @Nullable [] findBytes(String name, Function<String, byte @Nullable []> more) {
        Optional<byte[]> known = this.read.get(name);
        if (known != null) {
            return known.orElse(null);
        }
        int paths = PATHS.get();
        if (Integer.valueOf(paths).equals(this.missing.get(name))) {
            return null;
        }
        if (this.folding.isFolded(name)) {
            this.read.put(name, Optional.empty());
            return null;
        }
        byte[] found = more.apply(name);
        if (found == null) {
            this.missing.put(name, paths);
            return null;
        }
        byte[] folded = this.folding.fold(found);
        this.read.put(name, Optional.of(folded));
        return folded;
    }

    public void replace(Map<String, byte[]> classes) {
        classes.forEach((name, bytes) -> this.read.put(name, Optional.of(bytes)));
        synchronized (BOOTING) {
            this.discard();
        }
    }

    public void forgetReads() {
        this.read.clear();
        this.missing.clear();

        synchronized (BOOTING) {
            this.discard();
        }
    }

    public void foldInto(ExtensionFolder folding) {
        this.folding = folding;
    }

    public void learn(Decorations more) {
        Map<String, String> modIds = new HashMap<>(this.decorations.modIds());
        Map<String, Integer> levels = new HashMap<>(this.decorations.levels());
        modIds.putAll(more.modIds());
        levels.putAll(more.levels());
        this.decorations = new Decorations(modIds, levels, this.decorations.orders());
    }

    public record MixResult(Map<String, byte[]> classes, Map<String, String> argsShapes,
            Map<String, byte[]> generated) {
        public MixResult {
            classes = Map.copyOf(classes);
            argsShapes = Map.copyOf(argsShapes);
            generated = Map.copyOf(generated);
        }
    }

    public Map<String, byte[]> mix(Set<String> targets, Map<String, byte[]> classes, Map<String, byte[]> resources,
            List<String> configs) throws MixinCopyException {
        return this.mixAll(targets, classes, resources, configs).classes();
    }

    public void runPluginsLiveWith(Function<String, @Nullable Object> plugins,
            BiFunction<String, String, @Nullable Object> mixins) {
        this.livePlugins = plugins;
        this.liveMixins = mixins;
    }

    public void remapWith(@Nullable String obfuscationContext) {
        this.obfuscationContext = obfuscationContext;
    }

    public void setLevel(@Nullable String compatibility) {
        this.compatibility = compatibility;
    }

    public MixResult mixAll(Set<String> targets, Map<String, byte[]> classes, Map<String, byte[]> resources,
            List<String> configs) throws MixinCopyException {
        return this.mixAll(targets, classes, resources, configs, () -> false);
    }

    public MixResult mixAll(Set<String> targets, Map<String, byte[]> classes, Map<String, byte[]> resources,
            List<String> configs, BooleanSupplier stop) throws MixinCopyException {
        synchronized (BOOTING) {
            String service = System.getProperty(SERVICE);
            String bootstrap = System.getProperty(BOOTSTRAP);
            System.setProperty(SERVICE, COPY + "CopyMixinService");
            System.setProperty(BOOTSTRAP, COPY + "CopyMixinBootstrap");
            mixing = new Span(Thread.currentThread().getName(), System.currentTimeMillis(), Long.MAX_VALUE);
            try {
                long start = System.nanoTime();
                Copy copy = this.resetReusable();
                boolean reused = copy != null;
                long reset = System.nanoTime();
                if (copy == null) {
                    copy = this.open();
                }
                long loaded = System.nanoTime();
                Object[] answer = this.mixIn(copy, targets, classes, resources, configs, stop);
                this.reusable = copy;
                @SuppressWarnings("unchecked")
                Map<String, byte[]> mixed = Map.copyOf((Map<String, byte[]>) answer[0]);
                long[] stages = (long[]) answer[1];
                @SuppressWarnings("unchecked")
                Map<String, String> argsShapes = (Map<String, String>) answer[2];
                @SuppressWarnings("unchecked")
                Map<String, byte[]> generated = (Map<String, byte[]>) answer[3];
                LOGGER.info("[Hotwire] Mixin's {} copy mixed {} classes with {} configs in {} ms (reset {}, load {}, "
                        + "start {}, read {}, prepare {}, patch {})", reused ? "reused" : "fresh", targets.size(),
                        configs.size(), (System.nanoTime() - start) / 1_000_000, (reset - start) / 1_000_000,
                        (loaded - reset) / 1_000_000, stages[0] / 1_000_000, stages[1] / 1_000_000,
                        stages[2] / 1_000_000, stages[3] / 1_000_000);
                return new MixResult(mixed, argsShapes, generated);
            } finally {
                Span ended = mixing;
                if (ended != null) {
                    SPANS.addLast(new Span(ended.thread(), ended.from(), System.currentTimeMillis()));
                }
                while (SPANS.size() > SPAN_COUNT) {
                    SPANS.pollFirst();
                }
                mixing = null;
                restore(SERVICE, service);
                restore(BOOTSTRAP, bootstrap);
            }
        }
    }

    public static boolean isMixingAt(String thread, long millis) {
        Span now = mixing;
        if (now != null && now.holds(thread, millis)) {
            return true;
        }
        for (Span one : SPANS) {
            if (one.holds(thread, millis)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        synchronized (BOOTING) {
            this.discard();
        }
    }

    private @Nullable Copy resetReusable() {
        Copy copy = this.reusable;
        if (copy == null) {
            return null;
        }
        try {
            copy.reset().run();
            return copy;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("[Hotwire] Mixin's copy could not be reset for another mix, so a cold copy mixes instead: {}",
                    e.toString());
            this.discard();
            return null;
        }
    }

    private Object[] mixIn(Copy copy, Set<String> targets, Map<String, byte[]> classes,
            Map<String, byte[]> resources, List<String> configs, BooleanSupplier stop) throws MixinCopyException {
        Decorations decorated = this.decorations;
        try {
            Map<String, byte[]> folded = new HashMap<>();
            classes.forEach((name, bytes) -> folded.put(name, decorated == null ? bytes : this.folding.fold(bytes)));
            return copy.mixer().apply(new Object[] {List.copyOf(targets), Map.copyOf(folded), Map.copyOf(resources),
                    List.copyOf(configs), this.sessionId, this.live, this.sharedPackages, this.more,
                    (BiConsumer<String, String>) MixinCopy::log, this.side, decorated.modIds(),
                    decorated.levels(), this.obfuscationContext, this.compatibility, stop, decorated.orders(),
                    this.livePlugins, this.liveMixins});
        } catch (IllegalStateException e) {
            this.discard(copy);
            throw new MixinCopyException(e.getMessage(), e);
        } catch (RuntimeException | LinkageError | ServiceConfigurationError e) {
            this.discard(copy);
            throw new MixinCopyException("Mixin's copy failed: " + e, e);
        }
    }

    private void discard() {
        Copy copy = this.reusable;
        if (copy != null) {
            this.discard(copy);
        }
    }

    private void discard(Copy copy) {
        this.reusable = null;
        closeLoader(copy.loader());
    }

    private static void closeLoader(URLClassLoader loader) {
        try {
            loader.close();
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] Mixin's copy could not be closed: {}", e.getMessage());
        }
    }

    private static void log(String level, String message) {
        if (level.equals("WARN")) {
            LOGGER.info("[Hotwire] Mixin's copy, {}: {}", level, message);
        } else {
            LOGGER.error("[Hotwire] Mixin's copy, {}: {}", level, message);
        }
    }

    private Copy open() throws MixinCopyException {
        List<URL> path = new ArrayList<>();
        path.add(toUrl(copyJar()));
        path.addAll(this.mixinJars);
        URLClassLoader loader = new URLClassLoader(path.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        try {
            @SuppressWarnings("unchecked")
            Function<Object[], Object[]> mixer = load(loader, "CopyMixer", Function.class);
            return new Copy(loader, mixer, load(loader, "CopyMixinReset", Runnable.class));
        } catch (MixinCopyException e) {
            closeLoader(loader);
            throw e;
        }
    }

    private static <T> T load(ClassLoader copy, String name, Class<T> as) throws MixinCopyException {
        try {
            return as.cast(copy.loadClass(COPY + name).getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            throw new MixinCopyException("Mixin's copy could not be loaded: " + e, e);
        }
    }

    private static synchronized Path copyJar() throws MixinCopyException {
        Path jar = copyJar;
        if (jar != null) {
            return jar;
        }
        try (InputStream in = MixinCopy.class.getResourceAsStream("mixin-copy.jar")) {
            if (in == null) {
                throw new MixinCopyException("Hotwire's jar has no mixin-copy.jar", null);
            }
            jar = Files.createTempFile("hotwire-mixin-copy", ".jar");
            jar.toFile().deleteOnExit();
            Files.copy(in, jar, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new MixinCopyException("mixin-copy.jar could not be written out: " + e.getMessage(), e);
        }
        copyJar = jar;
        return jar;
    }

    private static void restore(String key, @Nullable String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new UncheckedIOException(new IOException(path + " is not a file", e));
        }
    }
}
