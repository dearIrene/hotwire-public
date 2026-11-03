package dev.moon.hotwire.mixin_copy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.ref.SoftReference;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
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
    private static int swapped;
    private static @Nullable String gameService;
    private static @Nullable String gameBootstrap;

    private static final ConcurrentLinkedDeque<Span> SPANS = new ConcurrentLinkedDeque<>();
    private static final int SPAN_COUNT = 8;
    private static final Set<Span> MIXING = ConcurrentHashMap.newKeySet();

    private record Span(String thread, long from, long to) {
        boolean holds(String name, long millis) {
            return this.thread.equals(name) && millis >= this.from && millis <= this.to;
        }
    }
    private static @Nullable Path copyJar;

    private static final ThreadLocal<@Nullable Set<String>> READ_IN_MIX = new ThreadLocal<>();
    private volatile byte @Nullable [] recipe;

    private final List<Path> mixinJars;
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

    private final Map<String, SoftReference<byte[]>> read = new ConcurrentHashMap<>();
    private final Map<String, byte[]> replaced = new ConcurrentHashMap<>();
    private final AtomicInteger readChanges = new AtomicInteger();
    private static final ThreadLocal<Map<String, byte[]>> REPLACING = new ThreadLocal<>();

    private final Map<String, Integer> missing = new ConcurrentHashMap<>();
    private static final AtomicInteger PATHS = new AtomicInteger();

    private static final String DEFAULT_REFMAP = "mixin.refmap.json";

    @SuppressWarnings("ArrayRecordComponent")
    private record DefaultRefmap(int paths, byte @Nullable [] bytes) {
    }

    private volatile @Nullable DefaultRefmap defaultRefmap;

    private final List<Copy> reusable = new ArrayList<>();
    private int generation;

    private static final int MAX_REUSABLE = 1;

    public MixinCopy(List<Path> mixinJars, ClassLoader live, List<String> sharedPackages, String sessionId,
            Function<String, byte @Nullable []> more, String side, Decorations decorations) {
        this.side = side;
        this.decorations = decorations;
        this.more = name -> {
            Set<String> reading = READ_IN_MIX.get();
            if (reading != null) {
                reading.add(name);
            }
            return this.findBytes(name, more);
        };
        this.mixinJars = List.copyOf(mixinJars);
        this.live = live;
        this.sharedPackages = List.copyOf(sharedPackages);
        this.sessionId = sessionId;
    }

    public static void markClassPathChanged() {
        PATHS.incrementAndGet();
    }

    public static int findClassPathChanges() {
        return PATHS.get();
    }

    private byte @Nullable [] findBytes(String name, Function<String, byte @Nullable []> more) {
        Map<String, byte[]> coming = REPLACING.get();
        byte[] next = coming == null ? null : coming.get(name);
        if (next != null) {
            return next;
        }
        byte[] updated = this.replaced.get(name);
        if (updated != null) {
            return updated;
        }
        SoftReference<byte[]> known = this.read.get(name);
        byte[] cached = known == null ? null : known.get();
        if (cached != null) {
            return cached;
        }
        int paths = PATHS.get();
        if (Integer.valueOf(paths).equals(this.missing.get(name)) || this.folding.isFolded(name)) {
            return null;
        }
        byte[] found = more.apply(name);
        if (found == null) {
            this.missing.put(name, paths);
            return null;
        }
        byte[] folded = this.folding.fold(found);
        this.read.put(name, new SoftReference<>(folded));
        return folded;
    }

    private static @Nullable Path findOwnJar() {
        try {
            CodeSource source = MixinCopy.class.getProtectionDomain().getCodeSource();
            Path path = source == null ? null : Path.of(source.getLocation().toURI());
            return path != null && Files.isRegularFile(path) ? path : null;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    public byte[] getRecipe() {
        byte[] known = this.recipe;
        if (known != null) {
            return known;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(copyJar()));
            for (Path jar : this.mixinJars) {
                digest.update(Files.readAllBytes(jar));
            }

            Path own = findOwnJar();
            if (own != null) {
                addEntryCode(digest, own);
            }
            digest.update(this.side.getBytes(StandardCharsets.UTF_8));

            known = digest.digest();
        } catch (IOException | MixinCopyException | NoSuchAlgorithmException e) {
            known = new byte[0];
        }
        this.recipe = known;
        return known;
    }

    private static void addEntryCode(MessageDigest digest, Path own) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(own))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                if (isEntryCode(name)) {
                    digest.update(name.getBytes(StandardCharsets.UTF_8));
                    byte[] bytes = zip.readAllBytes();
                    digest.update(name.endsWith(".class") ? stripDebug(bytes) : bytes);
                }
            }
        }
    }

    static boolean isEntryCode(String name) {
        return name.startsWith("dev/moon/hotwire/mixin_copy/") || name.startsWith("dev/moon/hotwire/cache/")
                || name.startsWith("dev/moon/hotwire/redefine/ClassPacks")
                || name.startsWith("dev/moon/hotwire/redefine/MixedCache")
                || name.startsWith("dev/moon/hotwire/redefine/CachedMixes");
    }

    static byte[] stripDebug(byte[] bytes) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(writer, ClassReader.SKIP_DEBUG);
        return writer.toByteArray();
    }

    public String findDecoration(String config) {
        Decorations now = this.decorations;
        return now.modIds().get(config) + "|" + now.levels().get(config) + "|" + now.orders().get(config);
    }

    public Map<String, byte @Nullable []> findReadBytes(Set<String> read) {
        Map<String, byte @Nullable []> found = new TreeMap<>();
        for (String name : read) {
            found.put(name, this.findBytes(name, this.more));
        }
        return found;
    }

    public String getSide() {
        return this.side;
    }

    public int findReadChanges() {
        return this.readChanges.get() + PATHS.get();
    }

    public byte @Nullable [] findReadable(String name) {
        return this.more.apply(name);
    }

    public void startReplacing(Map<String, byte[]> classes) {
        this.readChanges.incrementAndGet();
        REPLACING.set(classes);
    }

    public void endReplacing() {
        REPLACING.remove();
        this.readChanges.incrementAndGet();
    }

    public void replace(Map<String, byte[]> classes) {
        this.readChanges.incrementAndGet();
        this.replaced.putAll(classes);
        this.defaultRefmap = null;
        synchronized (BOOTING) {
            this.discard();
        }
    }

    public void restore(Set<String> names, Map<String, byte[]> before) {
        names.forEach(this.replaced::remove);
        this.replace(before);
    }

    public void forgetReads() {
        this.readChanges.incrementAndGet();
        this.read.clear();
        this.missing.clear();
        this.defaultRefmap = null;

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
            Map<String, byte[]> generated, Set<String> read) {
        public MixResult {
            classes = Map.copyOf(classes);
            argsShapes = Map.copyOf(argsShapes);
            generated = Map.copyOf(generated);
            read = Set.copyOf(read);
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
        Set<String> reading = new HashSet<>();
        READ_IN_MIX.set(reading);
        swapProperties();
        Span span = new Span(Thread.currentThread().getName(), System.currentTimeMillis(), Long.MAX_VALUE);
        MIXING.add(span);
        try {
            long start = System.nanoTime();
            int opened;
            Copy copy;
            synchronized (BOOTING) {
                opened = this.generation;
                copy = this.reusable.isEmpty() ? null : this.reusable.removeLast();
            }
            copy = copy == null ? null : this.reset(copy);
            boolean reused = copy != null;
            long reset = System.nanoTime();
            if (copy == null) {
                copy = this.open();
            }
            long loaded = System.nanoTime();
            Object[] answer = this.mixIn(copy, targets, classes, resources, configs, stop);
            this.giveBack(copy, opened);
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
            return new MixResult(mixed, argsShapes, generated, reading);
        } finally {
            READ_IN_MIX.remove();
            MIXING.remove(span);
            SPANS.addLast(new Span(span.thread(), span.from(), System.currentTimeMillis()));
            while (SPANS.size() > SPAN_COUNT) {
                SPANS.pollFirst();
            }
            restoreProperties();
        }
    }

    private static void swapProperties() {
        synchronized (BOOTING) {
            if (swapped++ == 0) {
                gameService = System.getProperty(SERVICE);
                gameBootstrap = System.getProperty(BOOTSTRAP);
                System.setProperty(SERVICE, COPY + "CopyMixinService");
                System.setProperty(BOOTSTRAP, COPY + "CopyMixinBootstrap");
            }
        }
    }

    private static void restoreProperties() {
        synchronized (BOOTING) {
            if (--swapped == 0) {
                restoreProperty(SERVICE, gameService);
                restoreProperty(BOOTSTRAP, gameBootstrap);
            }
        }
    }

    public static boolean isMixingAt(String thread, long millis) {
        for (Span now : MIXING) {
            if (now.holds(thread, millis)) {
                return true;
            }
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

    private @Nullable Copy reset(Copy copy) {
        try {
            copy.reset().run();
            return copy;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("[Hotwire] Mixin's copy could not be reset for another mix, so a cold copy mixes instead: {}",
                    e.toString());
            closeLoader(copy.loader());
            return null;
        }
    }

    private void giveBack(Copy copy, int opened) {
        synchronized (BOOTING) {
            if (opened == this.generation && this.reusable.size() < MAX_REUSABLE) {
                this.reusable.add(copy);
                return;
            }
        }
        closeLoader(copy.loader());
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
                    this.livePlugins, this.liveMixins, (Function<String, @Nullable InputStream>) this::findLive});
        } catch (IllegalStateException e) {
            this.discard(copy);
            throw new MixinCopyException(e.getMessage(), e);
        } catch (RuntimeException | LinkageError | ServiceConfigurationError e) {
            this.discard(copy);
            throw new MixinCopyException("Mixin's copy failed: " + e, e);
        }
    }

    private @Nullable InputStream findLive(String name) {
        if (!name.equals(DEFAULT_REFMAP)) {
            return this.live.getResourceAsStream(name);
        }
        int paths = PATHS.get();
        DefaultRefmap known = this.defaultRefmap;
        if (known == null || known.paths() != paths) {
            try (InputStream in = this.live.getResourceAsStream(name)) {
                known = new DefaultRefmap(paths, in == null ? null : in.readAllBytes());
            } catch (IOException e) {
                return this.live.getResourceAsStream(name);
            }
            this.defaultRefmap = known;
        }
        byte[] bytes = known.bytes();
        return bytes == null ? null : new ByteArrayInputStream(bytes);
    }

    private void discard() {
        this.generation++;
        this.reusable.forEach(copy -> closeLoader(copy.loader()));
        this.reusable.clear();
    }

    private void discard(Copy copy) {
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
        if (level.equals("WARN") || level.equals("INFO")) {
            LOGGER.info("[Hotwire] Mixin's copy, {}: {}", level, message);
        } else {
            LOGGER.error("[Hotwire] Mixin's copy, {}: {}", level, message);
        }
    }

    private Copy open() throws MixinCopyException {
        List<URL> path = new ArrayList<>();
        path.add(toUrl(copyJar()));
        this.mixinJars.forEach(jar -> path.add(toUrl(jar)));
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
            jar = MixinCopyJar.write(in.readAllBytes());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new MixinCopyException("mixin-copy.jar could not be written out: " + e.getMessage(), e);
        }
        copyJar = jar;
        return jar;
    }

    private static void restoreProperty(String key, @Nullable String value) {
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
