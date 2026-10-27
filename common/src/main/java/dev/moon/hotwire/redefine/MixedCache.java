package dev.moon.hotwire.redefine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.moon.hotwire.cache.CacheKey;
import dev.moon.hotwire.cache.CacheStore;
import dev.moon.hotwire.classfile.ClassHeader;
import dev.moon.hotwire.mixin_copy.ConfigTrim;
import dev.moon.hotwire.mixin_copy.MixinClassInfo;
import dev.moon.hotwire.mixin_copy.MixinCopy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class MixedCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(MixedCache.class);
    private static final String LIVE = "LivePluginShape";
    private static final String PLUGIN = "\"plugin\"";
    private static final Pattern ARGS = Pattern.compile("org/spongepowered/asm/synthetic/args/Args\\$\\d+");
    private static final long FLUSH_MILLIS = 2000;

    interface PluginAnswers {
        @Nullable Boolean ask(String config, String target, String mixin);
    }

    private static final class ReadHash {
        private final byte[] bytes;
        private final long hash;

        ReadHash(byte[] bytes, long hash) {
            this.bytes = bytes;
            this.hash = hash;
        }
    }

    private record Config(String name, CacheKey settings, Set<String> mixins, boolean plugin) {
    }

    private final CacheStore store;

    private final ClassPacks packs;
    private final MixinCopy copy;
    private final PluginAnswers answers;
    private final AtomicBoolean flushQueued = new AtomicBoolean();

    private final Set<String> uncached = ConcurrentHashMap.newKeySet();

    private final Map<String, Set<String>> aims = new ConcurrentHashMap<>();
    private final Map<CacheKey, Config> parsed = new ConcurrentHashMap<>();
    private final Map<String, ReadHash> hashes = new ConcurrentHashMap<>();
    private final Set<CacheKey> holding = ConcurrentHashMap.newKeySet();

    private final AtomicLong classesSaved = new AtomicLong();
    private final AtomicLong classesBefore = new AtomicLong();
    private final AtomicLong readsSaved = new AtomicLong();
    private final AtomicLong readLists = new AtomicLong();

    private final AtomicLong basesSaved = new AtomicLong();
    private final AtomicLong basesBefore = new AtomicLong();

    private final Set<CacheKey> counted = ConcurrentHashMap.newKeySet();
    @SuppressWarnings("ThreadPriorityCheck")
    private final ExecutorService writer = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("hotwire-cache").daemon().priority(Thread.MIN_PRIORITY).factory());

    MixedCache(CacheStore store, MixinCopy copy, PluginAnswers answers) {
        this.store = store;
        this.copy = copy;
        this.answers = answers;
        this.packs = new ClassPacks(store);
    }

    private static final String KEYS = "mixed class 8";

    private static final Map<byte[], CacheKey> SHAPES = Collections.synchronizedMap(new WeakHashMap<>());

    private static final @Nullable String KEY_DUMP = System.getProperty("hotwire.cacheKeyDump");

    private static synchronized void dumpKey(String line) {
        try {
            Files.writeString(Path.of(Objects.requireNonNull(KEY_DUMP)), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the key dump could not be written: {}", e.toString());
        }
    }

    Map<String, CacheKey> findKeys(Set<String> targets, Map<String, byte[]> classes, Map<String, byte[]> resources,
            List<String> configNames) {
        List<Config> configs = new ArrayList<>();
        for (String name : configNames) {
            byte[] json = resources.get(name);

            if (json == null) {
                if (this.uncached.add(name)) {
                    LOGGER.info("[Hotwire] the switch cache cannot read {}: classes it patches are mixed afresh", name);
                }
                return Map.of();
            }
            configs.add(this.readConfig(name, json, resources));
        }
        Map<String, CacheKey> keys = new LinkedHashMap<>();
        for (String target : targets) {
            byte[] original = classes.get(target);
            StringBuilder trace = KEY_DUMP == null ? null : new StringBuilder();
            CacheKey key = original == null ? null : this.findKey(target, original, configs,
                    this.findHandedAbove(original, targets, classes), this.findMixedAbove(original, targets, classes),
                    trace);
            if (key != null) {
                keys.put(target, key);
            }
            if (trace != null && key != null) {
                dumpKey(target + " " + Long.toHexString(key.high()) + " batch=" + targets.size() + trace + "\n");
            }
        }
        return keys;
    }

    private CacheKey findHandedAbove(byte[] original, Set<String> targets, Map<String, byte[]> classes) {
        CacheKey.Builder handed = CacheKey.of("handed above 1");
        Set<String> seen = new HashSet<>();
        List<byte[]> next = new ArrayList<>(List.of(original));
        while (!next.isEmpty()) {
            for (String type : ClassHeader.of(next.removeLast()).findAbove()) {
                byte[] bytes = classes.get(type);
                if (bytes != null && !targets.contains(type) && seen.add(type)) {
                    CacheKey shape = findShape(bytes);
                    handed.add(type).add(shape.high()).add(shape.low());
                    next.add(bytes);
                }
            }
        }
        return handed.build();
    }

    private static CacheKey findShape(byte[] bytes) {
        CacheKey known = SHAPES.get(bytes);
        if (known != null) {
            return known;
        }
        ClassHeader header = ClassHeader.of(bytes);
        CacheKey.Builder shape = CacheKey.of("shape 2").add(String.valueOf(header.superName()))
                .add(header.access());
        new TreeSet<>(header.interfaces()).forEach(shape::add);
        List<String> members = new ArrayList<>();
        header.fields().forEach(field -> members.add("f " + GeneratedNames.mask(field.name()) + field.desc()
                + " " + field.access()));
        header.methods().forEach(method -> members.add("m " + GeneratedNames.mask(method.name()) + method.desc()
                + " " + method.access()));
        members.sort(null);
        members.forEach(shape::add);
        CacheKey made = shape.build();
        SHAPES.put(bytes, made);
        return made;
    }

    private Set<String> findMixedAbove(byte[] original, Set<String> targets, Map<String, byte[]> classes) {
        Set<String> found = new TreeSet<>();
        Set<String> seen = new HashSet<>();
        List<byte[]> next = new ArrayList<>(List.of(original));
        while (!next.isEmpty()) {
            for (String type : ClassHeader.of(next.removeLast()).findAbove()) {
                if (!seen.add(type) || type.startsWith("java/")) {
                    continue;
                }
                byte[] bytes = classes.get(type);
                if (bytes == null) {
                    bytes = this.copy.findReadable(type);
                }
                if (targets.contains(type)) {
                    found.add(type);
                }
                if (bytes != null) {
                    next.add(bytes);
                }
            }
        }
        return found;
    }

    private @Nullable CacheKey findKey(String target, byte[] original, List<Config> configs, CacheKey others,
            Set<String> mixedAbove, @Nullable StringBuilder trace) {
        CacheKey.Builder key = CacheKey.of(KEYS).add(this.copy.getRecipe()).add(target).add(original)
                .add(others.high()).add(others.low());
        if (trace != null) {
            trace.append(" handed=").append(Long.toHexString(others.high())).append(" above=").append(mixedAbove);
        }
        List<String> types = new ArrayList<>();
        types.add(target);
        types.addAll(mixedAbove);
        for (Config config : configs) {
            List<String> aiming = new ArrayList<>();
            for (String mixin : config.mixins()) {
                Set<String> aims = this.findAims(mixin);
                if (aims == null) {
                    return null;
                }
                for (String type : types) {
                    if (aims.contains(type)) {
                        aiming.add(type + " " + mixin);
                    }
                }
            }
            if (aiming.isEmpty()) {
                continue;
            }
            key.add(config.settings().high()).add(config.settings().low());
            if (trace != null) {
                trace.append(" | ").append(config.name()).append(' ').append(Long.toHexString(config.settings().high()))
                        .append(' ').append(aiming.size());
            }
            for (String aimed : aiming) {
                key.add(aimed);
                if (config.plugin()) {
                    int space = aimed.indexOf(' ');
                    Boolean answer = this.answers.ask(config.name(), aimed.substring(0, space).replace('/', '.'),
                            aimed.substring(space + 1).replace('/', '.'));
                    if (answer == null) {
                        if (this.uncached.add(config.name())) {
                            LOGGER.info("[Hotwire] classes {} patches are mixed afresh each time: the game holds no"
                                    + " plugin of it to ask", config.name());
                        }
                        return null;
                    }
                    key.add(answer ? 1 : 0);
                    if (trace != null && !answer) {
                        trace.append(" no:").append(aimed.substring(aimed.indexOf(' ') + 1));
                    }
                }
            }
        }
        return key.build();
    }

    private Config readConfig(String name, byte[] json, Map<String, byte[]> resources) {
        String refmapName = ConfigTrim.readRefmapOf(json);
        byte[] refmap = refmapName == null ? null : resources.get(refmapName);
        CacheKey bytes = CacheKey.of("config bytes 1").add(name).add(json)
                .add(refmap == null ? new byte[0] : refmap).build();
        return this.parsed.computeIfAbsent(bytes, any -> {
            String text = new String(json, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            root.remove("mixins");
            root.remove("client");
            root.remove("server");
            Set<String> mixins = new TreeSet<>();
            for (String listed : ConfigTrim.readListed(json, this.copy.getSide())) {
                mixins.add(listed.replace('.', '/'));
            }
            CacheKey settings = CacheKey.of("config 1").add(name).add(root.toString())
                    .add(refmap == null ? new byte[0] : refmap).build();
            return new Config(name, settings, mixins, text.contains(LIVE) || text.contains(PLUGIN));
        });
    }

    private @Nullable Set<String> findAims(String mixin) {
        Set<String> known = this.aims.get(mixin);
        if (known != null) {
            return known;
        }
        byte[] bytes = this.copy.findReadable(mixin);
        if (bytes == null) {
            return null;
        }
        Set<String> read = MixinClassInfo.read(bytes).targets();
        this.aims.put(mixin, read);
        return read;
    }

    MixinCopy.@Nullable MixResult find(CacheKey key, String target, byte[] original) {
        byte[] listed = this.store.get(key, null);
        if (listed == null) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(listed))) {
            CacheKey reads = new CacheKey(in.readLong(), in.readLong());
            if (!this.isHolding(reads)) {
                return null;
            }
            List<CacheKey> used = new ArrayList<>();
            used.add(key);
            ClassPacks.Read read = this.packs.get(new CacheKey(in.readLong(), in.readLong()), original, used);
            if (read == null) {
                return null;
            }
            byte[] mixed = read.bytes();
            this.count(used, key, original.length, read.base());
            Map<String, String> argsShapes = new LinkedHashMap<>();
            for (int count = in.readInt(); count > 0; count--) {
                argsShapes.put(in.readUTF(), in.readUTF());
            }
            Map<String, byte[]> generated = new LinkedHashMap<>();
            for (int count = in.readInt(); count > 0; count--) {
                String name = in.readUTF();
                byte[] bytes = this.store.get(findPartKey(key, name), null);
                if (bytes == null) {
                    return null;
                }
                generated.put(name, bytes);
            }
            return new MixinCopy.MixResult(Map.of(target, mixed), argsShapes, generated);
        } catch (IOException e) {
            return null;
        }
    }

    private void count(List<CacheKey> keys, CacheKey entry, int before, boolean base) {
        boolean first = !this.counted.contains(entry);
        long bytes = 0;
        for (CacheKey one : keys) {
            if (this.counted.add(one)) {
                bytes += this.store.findSize(one);
            }
        }
        this.classesSaved.addAndGet(bytes);
        if (first) {
            this.classesBefore.addAndGet(before);
            if (base) {
                this.basesSaved.addAndGet(bytes);
                this.basesBefore.addAndGet(before);
            }
        }
    }

    private boolean isHolding(CacheKey reads) {
        if (this.holding.contains(reads)) {
            return true;
        }
        byte[] list = this.store.get(reads, null);
        if (list == null) {
            return false;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(list))) {
            for (int count = in.readInt(); count > 0; count--) {
                String name = in.readUTF();
                if (this.hashOf(name, this.copy.findReadable(name)) != in.readLong()) {
                    return false;
                }
            }
        } catch (IOException e) {
            return false;
        }
        this.holding.add(reads);
        return true;
    }

    void save(Map<String, CacheKey> keys, MixinCopy.MixResult made, Map<String, byte[]> classes) {
        Map<String, byte @Nullable []> read = this.copy.takeReadInMix();
        Map<String, byte[]> originals = new HashMap<>(classes);
        this.writer.execute(() -> {
            CacheKey reads = this.saveReads(read);
            if (reads == null) {
                return;
            }
            keys.forEach((target, key) -> {
                byte[] mixed = made.classes().get(target);
                if (mixed != null) {
                    this.write(key, reads, target, mixed, made, originals);
                }
            });
        });
        if (this.flushQueued.compareAndSet(false, true)) {
            this.writer.execute(() -> {
                try {
                    Thread.sleep(FLUSH_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                this.flushQueued.set(false);
                this.store.flush();
            });
        }
    }

    private @Nullable CacheKey saveReads(Map<String, byte @Nullable []> read) {
        ByteArrayOutputStream list = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(list)) {
            out.writeInt(read.size());
            for (Map.Entry<String, byte @Nullable []> one : new TreeMap<>(read).entrySet()) {
                out.writeUTF(one.getKey());
                out.writeLong(this.hashOf(one.getKey(), one.getValue()));
            }
        } catch (IOException e) {
            return null;
        }
        byte[] bytes = list.toByteArray();
        CacheKey reads = CacheKey.of("reads 1").add(bytes).build();
        this.store.put(reads, bytes, null);
        if (this.counted.add(reads)) {
            this.readsSaved.addAndGet(this.store.findSize(reads));
            this.readLists.incrementAndGet();
        }
        this.holding.add(reads);
        return reads;
    }

    private void write(CacheKey key, CacheKey reads, String target, byte[] mixed, MixinCopy.MixResult made,
            Map<String, byte[]> originals) {
        Set<String> uses = new HashSet<>();
        Matcher args = ARGS.matcher(new String(mixed, StandardCharsets.ISO_8859_1));
        while (args.find()) {
            uses.add(args.group());
        }
        ByteArrayOutputStream listed = new ByteArrayOutputStream();
        List<CacheKey> used = new ArrayList<>();
        ClassPacks.Body body;
        try (DataOutputStream out = new DataOutputStream(listed)) {
            out.writeLong(reads.high());
            out.writeLong(reads.low());
            body = this.packs.put(target, mixed, originals.get(target), used);
            out.writeLong(body.key().high());
            out.writeLong(body.key().low());
            Map<String, String> shapes = new TreeMap<>(made.argsShapes());
            shapes.keySet().retainAll(uses);
            out.writeInt(shapes.size());
            for (Map.Entry<String, String> shape : shapes.entrySet()) {
                out.writeUTF(shape.getKey());
                out.writeUTF(shape.getValue());
            }
            Map<String, byte[]> generated = new TreeMap<>(made.generated());
            generated.keySet().removeIf(name -> !name.startsWith(target + "$"));
            out.writeInt(generated.size());
            for (Map.Entry<String, byte[]> one : generated.entrySet()) {
                out.writeUTF(one.getKey());
                this.store.put(findPartKey(key, one.getKey()), one.getValue(), null);
            }
        } catch (IOException e) {
            return;
        }

        this.store.put(key, listed.toByteArray(), null);
        used.add(key);
        byte[] original = originals.get(target);
        this.count(used, key, original == null ? mixed.length : original.length, body.base());
    }

    long[] findSaved() {
        return new long[] {this.classesSaved.get(), this.classesBefore.get(), this.readsSaved.get(),
            this.readLists.get(), this.basesSaved.get(), this.basesBefore.get()};
    }

    String describe() {
        return this.store.describe();
    }

    CacheStore getStore() {
        return this.store;
    }

    long findFilled(CacheKey plan) {
        byte[] mark = this.store.get(this.findFilledKey(plan), null);
        return mark == null ? -1 : ByteBuffer.wrap(mark).getLong();
    }

    void markFilled(CacheKey plan, long bytes) {
        this.writer.execute(() -> {
            this.store.put(this.findFilledKey(plan), ByteBuffer.allocate(Long.BYTES).putLong(bytes).array(), null);
            this.store.flush();
        });
    }

    private CacheKey findFilledKey(CacheKey plan) {
        return CacheKey.of("filled 2").add(KEYS).add(this.copy.getRecipe()).add(plan.high()).add(plan.low()).build();
    }

    void clear() {
        this.writer.execute(() -> {
            this.store.clear();
            this.packs.forget();
            this.holding.clear();
            this.counted.clear();
            this.classesSaved.set(0);
            this.classesBefore.set(0);
            this.readsSaved.set(0);
            this.readLists.set(0);
            this.basesSaved.set(0);
            this.basesBefore.set(0);
        });
    }

    long getBytes() {
        return this.store.getBytes();
    }

    int getEntries() {
        return this.store.getEntries();
    }

    @SuppressWarnings("ReferenceEquality")
    private long hashOf(String name, byte @Nullable [] bytes) {
        if (bytes == null) {
            return 0;
        }
        ReadHash known = this.hashes.get(name);
        if (known != null && known.bytes == bytes) {
            return known.hash;
        }
        CacheKey key = CacheKey.of("read").add(bytes).build();
        long hash = key.high() ^ key.low();
        this.hashes.put(name, new ReadHash(bytes, hash));
        return hash;
    }

    private static CacheKey findPartKey(CacheKey key, String name) {
        return CacheKey.of("mixed part 1").add(key.high()).add(key.low()).add(name).build();
    }
}
