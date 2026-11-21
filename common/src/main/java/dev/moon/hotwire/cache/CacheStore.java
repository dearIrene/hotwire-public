package dev.moon.hotwire.cache;

import dev.moon.hotwire.config.AtomicFiles;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.CRC32C;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CacheStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(CacheStore.class);

    private static final Map<Path, CacheStore> OPEN = new ConcurrentHashMap<>();
    private static final int INDEX_MAGIC = 0x48574932;
    private static final int PACK_MAGIC = 0x48575031;

    private static final int RECORD = 16 + 4 + 8 + 4 + 4 + 4;
    private static final int HEADER = 4 + 4;
    private static final byte WITH_DICTIONARY = 1;

    private static final byte VALUE_KEYED = 4;
    private static final String SECRET = "HmacSHA256";
    private static final int SECRET_BYTES = 32;

    private static final int MOST_VALUE_BYTES = 1 << 28;

    private static final int MOST_PACKS = 16;

    private static final int STALE_STARTS = 20;

    private static final int FAR_STARTS = 400;

    private static final long DEAD_BYTES = 1L << 20;

    private final Path folder;
    private final String kind;

    private final Mac mac;

    private final int user;

    private final Map<Integer, List<Integer>> starts = new HashMap<>();
    private volatile long capBytes;
    private final Object lock = new Object();

    private final Object writing = new Object();

    private final Map<CacheKey, Place> places = new HashMap<>();

    private final Map<Integer, FileChannel> packs = new HashMap<>();

    private @Nullable ByteBuffer readBuffer;
    private static final int READ_BUFFER_MOST = 1 << 20;

    @SuppressWarnings("ArrayRecordComponent")
    private record WaitingEntry(byte[] packed, byte flags, int check) {
    }

    private final Map<CacheKey, WaitingEntry> pending = new TreeMap<>();
    private int generation;

    private int start;
    private boolean used;

    private boolean lost;
    private volatile long bytes;
    private long hits;
    private long misses;

    private record Place(int pack, long offset, int length, int lastUse, int user) {
    }

    private CacheStore(Path folder, String kind, long capBytes, int user, Mac mac) {
        this.folder = folder;
        this.kind = kind;
        this.mac = mac;
        this.capBytes = capBytes;
        this.user = user;
    }

    public static CacheStore open(Path folder, String kind, long capBytes) {
        return open(folder, kind, capBytes, Path.of("").toAbsolutePath().toString());
    }

    public static CacheStore open(Path folder, String kind, long capBytes, String instance) {
        CacheStore store = new CacheStore(folder, kind, capBytes, instance.hashCode(),
                makeMac(readSecret(folder, kind)));
        try {
            Files.createDirectories(folder);
            store.readHead();
        } catch (IOException | RuntimeException e) {
            LOGGER.info("[Hotwire] the {} cache starts empty: {}", kind, e.toString());
            store.places.clear();
            store.starts.clear();
            store.lost = true;
            store.start = Math.max(store.start, 1);
        }
        List<Integer> own = store.starts.computeIfAbsent(store.user, user -> new ArrayList<>());
        own.add(store.start);
        if (own.size() > STALE_STARTS) {
            own.subList(0, own.size() - STALE_STARTS).clear();
        }
        OPEN.put(folder.toAbsolutePath().normalize(), store);
        return store;
    }

    public static CacheStore of(Path folder, String kind) {
        CacheStore open = OPEN.get(folder.toAbsolutePath().normalize());
        return open != null ? open : open(folder, kind, Long.MAX_VALUE);
    }

    public byte @Nullable [] get(CacheKey key, byte @Nullable [] dictionary) {
        synchronized (this.lock) {
            WaitingEntry waiting = this.pending.get(key);
            if (waiting != null) {
                byte[] value = expand(waiting.packed(), (waiting.flags() & WITH_DICTIONARY) != 0 ? dictionary : null);
                if (value == null || check(value) != waiting.check()) {
                    this.misses++;
                    return null;
                }
                this.hits++;
                return value;
            }
            Place place = this.places.get(key);
            if (place == null) {
                this.misses++;
                return null;
            }
            byte[] read = this.read(key, place, dictionary);
            if (read == null) {
                this.places.remove(key);
                this.used = true;
                this.misses++;
                return null;
            }
            if (place.lastUse() != this.start || place.user() != this.user) {
                this.places.put(key, new Place(place.pack(), place.offset(), place.length(), this.start, this.user));
                this.used = true;
            }
            this.hits++;
            return read;
        }
    }

    public int findSize(CacheKey key) {
        synchronized (this.lock) {
            WaitingEntry waiting = this.pending.get(key);
            if (waiting != null) {
                return waiting.packed().length + 5;
            }
            Place place = this.places.get(key);
            return place == null ? 0 : place.length() + 5;
        }
    }

    public int put(CacheKey key, byte[] value, byte @Nullable [] dictionary) {
        byte[] packed = pack(value, dictionary);
        synchronized (this.lock) {
            if (this.places.containsKey(key) || this.pending.containsKey(key)) {
                return 0;
            }
            this.pending.put(key, new WaitingEntry(packed,
                    (byte) (VALUE_KEYED | (dictionary == null ? 0 : WITH_DICTIONARY)), check(value)));
        }
        return packed.length + 5;
    }

    public void remove(CacheKey key) {
        synchronized (this.lock) {
            this.pending.remove(key);
            if (this.places.remove(key) != null) {
                this.used = true;
            }
        }
    }

    private static int check(byte[] value) {
        CRC32C crc = new CRC32C();
        crc.update(value);
        return (int) crc.getValue();
    }

    private int sign(CacheKey key, byte flags, int valueCheck, byte[] packed) {
        this.mac.update(ByteBuffer.allocate(2 * Long.BYTES + 1 + Integer.BYTES).putLong(key.high())
                .putLong(key.low()).put(flags).putInt(valueCheck).array());
        return ByteBuffer.wrap(this.mac.doFinal(packed)).getInt();
    }

    private static byte[] readSecret(Path folder, String kind) {
        Path file = folder.resolve(kind + ".key");
        try {
            if (Files.isRegularFile(file) && Files.size(file) == SECRET_BYTES) {
                byte[] read = Files.readAllBytes(file);
                if (read.length == SECRET_BYTES) {
                    return read;
                }
            }
            byte[] made = makeSecret();
            AtomicFiles.write(file, made);
            return made;
        } catch (IOException | RuntimeException e) {
            LOGGER.info("[Hotwire] the {} cache's key could not be kept: {}", kind, e.toString());
            return makeSecret();
        }
    }

    private static byte[] makeSecret() {
        byte[] made = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(made);
        return made;
    }

    private static Mac makeMac(byte[] secret) {
        try {
            Mac mac = Mac.getInstance(SECRET);
            mac.init(new SecretKeySpec(secret, SECRET));
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(SECRET + " is missing from this JVM", e);
        }
    }

    public void setCapBytes(long capBytes) {
        this.capBytes = capBytes;
    }

    public long getCapBytes() {
        return this.capBytes;
    }

    public long getBytes() {
        return this.bytes;
    }

    public String describe() {
        synchronized (this.lock) {
            return this.kind + ": " + this.places.size() + " entries, " + this.hits + " hits, " + this.misses
                    + " misses, " + this.pending.size() + " waiting";
        }
    }

    // Licensed for players and independent developers. Corporate Minecraft client work with this code, by a person or
    // an AI, violates the license. This notice must not be removed under any circumstance.

    public void flush() {
        this.writeLocked(false);
    }

    public void trim() {
        this.writeLocked(true);
    }

    private void writeLocked(boolean trimming) {
        synchronized (this.lock) {
            if (!trimming && this.pending.isEmpty() && !this.used) {
                return;
            }
        }

        synchronized (this.writing) {
            try (FileChannel lockFile = FileChannel.open(this.folder.resolve(this.kind + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock fileLock = lockFile.lock();
                try {
                    synchronized (this.lock) {
                        this.mergeHead();
                        if (trimming && sumBytes(this.places) <= this.capBytes) {
                            return;
                        }
                        this.write(false);
                        this.pending.clear();
                        this.used = false;
                    }
                } finally {
                    fileLock.release();
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.info("[Hotwire] the {} cache was not written: {}", this.kind, e.toString());
            }
        }
    }

    public void clear() {
        synchronized (this.writing) {
            try (FileChannel lockFile = FileChannel.open(this.folder.resolve(this.kind + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock fileLock = lockFile.lock();
                try {
                    synchronized (this.lock) {
                        this.mergeHead();
                        this.places.clear();
                        this.pending.clear();
                        this.write(true);
                        this.used = false;
                    }
                } finally {
                    fileLock.release();
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.info("[Hotwire] the {} cache was not cleared: {}", this.kind, e.toString());
            }
        }
    }

    private static long sumBytes(Map<CacheKey, Place> places) {
        long total = 0;
        for (Place place : places.values()) {
            total += place.length() + 5;
        }
        return total;
    }

    private void readHead() throws IOException {
        Path head = this.folder.resolve(this.kind + ".head");
        if (Files.notExists(head)) {
            this.start = 1;
            return;
        }
        List<String> lines = Files.readAllLines(head, StandardCharsets.UTF_8);
        String[] first = lines.getFirst().trim().split(" ", -1);
        this.generation = Integer.parseInt(first[0]);
        this.start = Integer.parseInt(first[1]) + 1;
        this.readIndex(this.generation, this.places);
        this.starts.putAll(readStarts(lines));
        this.bytes = sumBytes(this.places);
    }

    private static Map<Integer, List<Integer>> readStarts(List<String> lines) {
        Map<Integer, List<Integer>> read = new HashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] words = line.trim().split(" ", -1);
            if (words.length < 2) {
                continue;
            }
            List<Integer> starts = new ArrayList<>();
            for (int at = 1; at < words.length; at++) {
                starts.add(Integer.parseInt(words[at]));
            }
            read.put(Integer.parseInt(words[0]), starts);
        }
        return read;
    }

    private boolean isStale(Place place) {
        if (place.lastUse() < this.start - FAR_STARTS) {
            return true;
        }
        List<Integer> theirs = this.starts.get(place.user());
        return theirs != null && theirs.size() >= STALE_STARTS && place.lastUse() < theirs.getFirst();
    }

    private void mergeHead() throws IOException {
        Path head = this.folder.resolve(this.kind + ".head");
        if (Files.notExists(head)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(head, StandardCharsets.UTF_8);
            int latest = Integer.parseInt(lines.getFirst().trim().split(" ", -1)[0]);
            if (latest != this.generation) {
                Map<CacheKey, Place> theirs = new HashMap<>();
                this.readIndex(latest, theirs);
                theirs.forEach(this.places::putIfAbsent);
                this.generation = Math.max(this.generation, latest);

                readStarts(lines).forEach((user, theirStarts) -> {
                    if (user != this.user) {
                        this.starts.put(user, theirStarts);
                    }
                });
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.info("[Hotwire] the {} cache's head could not be read: {}", this.kind, e.toString());
        }
    }

    private void readIndex(int at, Map<CacheKey, Place> into) throws IOException {
        Path index = this.folder.resolve(this.kind + "." + at + ".idx");
        ByteBuffer read = ByteBuffer.wrap(Files.readAllBytes(index));
        if (read.getInt() != INDEX_MAGIC) {
            throw new IOException("not an index: " + index.getFileName());
        }
        int count = read.getInt();
        for (int entry = 0; entry < count; entry++) {
            CacheKey key = new CacheKey(read.getLong(), read.getLong());
            Place place = new Place(read.getInt(), read.getLong(), read.getInt(), read.getInt(), read.getInt());
            if (place.offset() < 0 || place.length() < 0 || place.length() > MOST_VALUE_BYTES) {
                throw new IOException("a damaged index: " + index.getFileName());
            }
            into.put(key, place);
        }
    }

    private byte @Nullable [] read(CacheKey key, Place place, byte @Nullable [] dictionary) {
        byte[] raw = this.readRaw(place);
        if (raw == null) {
            return null;
        }
        try {
            ByteBuffer at = ByteBuffer.wrap(raw);
            int signed = at.getInt();
            byte flags = at.get();
            if ((flags & VALUE_KEYED) == 0) {
                return null;
            }
            byte[] packed = new byte[place.length()];
            at.get(packed);
            byte[] value = expand(packed, (flags & WITH_DICTIONARY) != 0 ? dictionary : null);
            return value == null || this.sign(key, flags, check(value), packed) != signed ? null : value;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void write(boolean empty) throws IOException {
        int next = this.generation + 1;
        Map<CacheKey, Place> written = new HashMap<>(this.places);
        written.values().removeIf(this::isStale);
        long total = sumBytes(written);
        for (WaitingEntry waiting : this.pending.values()) {
            total += waiting.packed().length + 5;
        }
        boolean full = total > this.capBytes;
        Set<Integer> named = new HashSet<>();
        written.values().forEach(place -> named.add(place.pack()));
        long dead = this.sumPackBytes(named) - sumBytes(written);
        boolean compact = empty || this.lost || full || named.size() >= MOST_PACKS
                || dead > Math.max(DEAD_BYTES, total / 4);
        long limit = full ? this.capBytes * 3 / 4 : Long.MAX_VALUE;
        boolean packed = compact || !this.pending.isEmpty();

        try (OutputStream pack = packed ? new BufferedOutputStream(
                Files.newOutputStream(this.folder.resolve(this.kind + "." + next + ".pack")), 1 << 16)
                : OutputStream.nullOutputStream()) {
            pack.write(ByteBuffer.allocate(4).putInt(PACK_MAGIC).array());
            long at = 4;
            if (compact) {
                List<Map.Entry<CacheKey, Place>> byUse = new ArrayList<>(written.entrySet());
                byUse.sort(Comparator.comparingInt((Map.Entry<CacheKey, Place> entry) -> entry.getValue().lastUse())
                        .reversed());
                written.clear();
                long copied = 0;
                for (Map.Entry<CacheKey, Place> entry : byUse) {
                    Place place = entry.getValue();
                    byte[] raw = this.readRaw(place);
                    if (raw == null || copied + raw.length > limit) {
                        continue;
                    }
                    copied += raw.length;
                    written.put(entry.getKey(), new Place(next, at, raw.length - 5, place.lastUse(), place.user()));
                    pack.write(raw);
                    at += raw.length;
                }
            }
            for (Map.Entry<CacheKey, WaitingEntry> entry : this.pending.entrySet()) {
                WaitingEntry waiting = entry.getValue();
                byte[] value = waiting.packed();
                ByteBuffer entryHeader = ByteBuffer.allocate(5)
                        .putInt(this.sign(entry.getKey(), waiting.flags(), waiting.check(), value))
                        .put(waiting.flags());
                written.put(entry.getKey(), new Place(next, at, value.length, this.start, this.user));
                pack.write(entryHeader.array());
                pack.write(value);
                at += 5 + value.length;
            }
        }
        ByteBuffer index = ByteBuffer.allocate(HEADER + written.size() * RECORD);
        index.putInt(INDEX_MAGIC).putInt(written.size());
        new TreeMap<>(written).forEach((key, place) -> index.putLong(key.high()).putLong(key.low())
                .putInt(place.pack()).putLong(place.offset()).putInt(place.length()).putInt(place.lastUse())
                .putInt(place.user()));

        AtomicFiles.write(this.folder.resolve(this.kind + "." + next + ".idx"), index.array());

        Set<Integer> users = new HashSet<>();
        written.values().forEach(place -> users.add(place.user()));
        this.starts.keySet().removeIf(user -> user != this.user && !users.contains(user));
        StringBuilder told = new StringBuilder().append(next).append(' ').append(this.start).append('\n');
        new TreeMap<>(this.starts).forEach((user, theirs) -> {
            told.append(user);
            theirs.forEach(one -> told.append(' ').append(one));
            told.append('\n');
        });
        Path head = this.folder.resolve(this.kind + ".head");
        AtomicFiles.writeString(head, told.toString());
        int before = this.generation;
        this.generation = next;
        this.places.clear();
        this.places.putAll(written);
        this.bytes = sumBytes(written);
        this.lost = false;
        this.dropUnused(before);
    }

    private long sumPackBytes(Set<Integer> named) {
        long total = 0;
        for (int pack : named) {
            try {
                total += Files.size(this.folder.resolve(this.kind + "." + pack + ".pack"));
            } catch (IOException | RuntimeException e) {
                LOGGER.debug("[Hotwire] the {} cache's pack {} could not be measured: {}", this.kind, pack,
                        e.toString());
            }
        }
        return total;
    }

    private byte @Nullable [] readRaw(Place place) {
        try {
            FileChannel pack = this.packs.get(place.pack());
            if (pack == null) {
                pack = FileChannel.open(this.folder.resolve(this.kind + "." + place.pack() + ".pack"),
                        StandardOpenOption.READ);
                this.packs.put(place.pack(), pack);
            }
            if (place.offset() + place.length() + 5 > pack.size()) {
                return null;
            }
            int need = place.length() + 5;
            ByteBuffer raw = need > READ_BUFFER_MOST ? ByteBuffer.allocate(need) : this.readBuffer;
            if (raw == null || raw.capacity() < need) {
                raw = ByteBuffer.allocateDirect(Math.max(need, 64 * 1024));
                this.readBuffer = raw;
            }
            raw.clear().limit(need);
            long at = place.offset();
            while (raw.hasRemaining()) {
                int read = pack.read(raw, at + raw.position());
                if (read < 0) {
                    return null;
                }
            }
            byte[] out = new byte[need];
            raw.flip().get(out);
            return out;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void dropUnused(int before) {
        Set<Integer> named = new HashSet<>();
        this.places.values().forEach(place -> named.add(place.pack()));
        List<Path> listed;
        try (Stream<Path> files = Files.list(this.folder)) {
            listed = files.toList();
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("[Hotwire] old {} cache files stay until the next flush (from {}): {}", this.kind, before,
                    e.toString());
            return;
        }
        for (Path file : listed) {
            String name = file.getFileName().toString();
            if (!name.startsWith(this.kind + ".")) {
                continue;
            }
            try {
                boolean oldIndex = name.endsWith(".idx") && !name.equals(this.kind + "." + this.generation + ".idx");
                boolean deadPack = name.endsWith(".pack") && !named.contains(this.parseGeneration(name));
                if (oldIndex || deadPack || name.endsWith(".tmp")) {
                    FileChannel open = deadPack ? this.packs.remove(this.parseGeneration(name)) : null;
                    if (open != null) {
                        open.close();
                    }
                    Files.deleteIfExists(file);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.debug("[Hotwire] the old {} cache file {} stays until the next flush: {}", this.kind, name,
                        e.toString());
            }
        }
    }

    private int parseGeneration(String name) {
        String middle = name.substring(this.kind.length() + 1, name.lastIndexOf('.'));
        return Integer.parseInt(middle);
    }

    private static byte[] pack(byte[] value, byte @Nullable [] dictionary) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
            if (dictionary != null) {
                deflater.setDictionary(dictionary, Math.max(0, dictionary.length - 32_768),
                        Math.min(dictionary.length, 32_768));
            }
            deflater.setInput(value);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(value.length / 3 + 16);
            byte[] chunk = new byte[16_384];
            while (!deflater.finished()) {
                out.write(chunk, 0, deflater.deflate(chunk));
            }
            return ByteBuffer.allocate(4 + out.size()).putInt(value.length).put(out.toByteArray()).array();
        } finally {
            deflater.end();
        }
    }

    private static byte @Nullable [] expand(byte[] packed, byte @Nullable [] dictionary) {
        ByteBuffer read = ByteBuffer.wrap(packed);
        int length = read.getInt();
        if (length < 0 || length > MOST_VALUE_BYTES) {
            return null;
        }
        Inflater inflater = new Inflater(true);
        try {
            if (dictionary != null) {
                inflater.setDictionary(dictionary, Math.max(0, dictionary.length - 32_768),
                        Math.min(dictionary.length, 32_768));
            }
            inflater.setInput(packed, 4, packed.length - 4);
            byte[] out = new byte[length];
            int made = 0;
            while (made < length && !inflater.finished()) {
                int step = inflater.inflate(out, made, length - made);
                if (step == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    return null;
                }
                made += step;
            }
            return made == length ? out : null;
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
    }
}
