package dev.moon.hotwire.cache;

import dev.moon.hotwire.config.AtomicFiles;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CacheStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(CacheStore.class);

    private static final Map<Path, CacheStore> OPEN = new ConcurrentHashMap<>();
    private static final int INDEX_MAGIC = 0x48574931;
    private static final int PACK_MAGIC = 0x48575031;

    private static final int RECORD = 16 + 4 + 8 + 4 + 4;
    private static final int HEADER = 4 + 4;
    private static final byte WITH_DICTIONARY = 1;

    private static final byte VALUE_CHECKED = 2;

    private static final int MOST_PACKS = 16;

    private static final int STALE_STARTS = 20;

    private final Path folder;
    private final String kind;
    private volatile long capBytes;
    private final Object lock = new Object();

    private final Map<CacheKey, Place> places = new HashMap<>();

    private final Map<Integer, FileChannel> packs = new HashMap<>();
    private final Map<CacheKey, byte[]> pending = new TreeMap<>();
    private final Map<CacheKey, Byte> pendingFlags = new HashMap<>();
    private final Map<CacheKey, Integer> pendingChecks = new HashMap<>();
    private int generation;

    private int start;
    private boolean used;

    private boolean lost;
    private volatile long bytes;
    private long hits;
    private long misses;

    private record Place(int pack, long offset, int length, int lastUse) {
    }

    private CacheStore(Path folder, String kind, long capBytes) {
        this.folder = folder;
        this.kind = kind;
        this.capBytes = capBytes;
    }

    public static CacheStore open(Path folder, String kind, long capBytes) {
        CacheStore store = new CacheStore(folder, kind, capBytes);
        try {
            Files.createDirectories(folder);
            store.readHead();
        } catch (IOException | RuntimeException e) {
            LOGGER.info("[Hotwire] the {} cache starts empty: {}", kind, e.toString());
            store.places.clear();
            store.lost = true;
            store.start = Math.max(store.start, 1);
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
            byte[] waiting = this.pending.get(key);
            if (waiting != null) {
                byte flags = this.pendingFlags.getOrDefault(key, (byte) 0);
                byte[] value = expand(waiting, (flags & WITH_DICTIONARY) != 0 ? dictionary : null);
                if (value == null || check(value) != this.pendingChecks.getOrDefault(key, 0)) {
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
            byte[] read = this.read(place, dictionary);
            if (read == null) {
                this.places.remove(key);
                this.used = true;
                this.misses++;
                return null;
            }
            if (place.lastUse() != this.start) {
                this.places.put(key, new Place(place.pack(), place.offset(), place.length(), this.start));
                this.used = true;
            }
            this.hits++;
            return read;
        }
    }

    public int findSize(CacheKey key) {
        synchronized (this.lock) {
            byte[] waiting = this.pending.get(key);
            if (waiting != null) {
                return waiting.length + 5;
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
            this.pending.put(key, packed);
            this.pendingFlags.put(key, (byte) (VALUE_CHECKED | (dictionary == null ? 0 : WITH_DICTIONARY)));
            this.pendingChecks.put(key, check(value));
        }
        return packed.length + 5;
    }

    public void remove(CacheKey key) {
        synchronized (this.lock) {
            this.pending.remove(key);
            this.pendingFlags.remove(key);
            this.pendingChecks.remove(key);
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
            try (FileChannel lockFile = FileChannel.open(this.folder.resolve(this.kind + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock fileLock = lockFile.lock();
                try {
                    this.mergeHead();
                    if (trimming && sumBytes(this.places) <= this.capBytes) {
                        return;
                    }
                    this.write(false);
                    this.pending.clear();
                    this.pendingFlags.clear();
                    this.pendingChecks.clear();
                    this.used = false;
                } finally {
                    fileLock.release();
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.info("[Hotwire] the {} cache was not written: {}", this.kind, e.toString());
            }
        }
    }

    public void clear() {
        synchronized (this.lock) {
            try (FileChannel lockFile = FileChannel.open(this.folder.resolve(this.kind + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock fileLock = lockFile.lock();
                try {
                    this.mergeHead();
                    this.places.clear();
                    this.pending.clear();
                    this.pendingFlags.clear();
                    this.pendingChecks.clear();
                    this.write(true);
                    this.used = false;
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
        String read = Files.readString(head, StandardCharsets.UTF_8).trim();
        int space = read.indexOf(' ');
        this.generation = Integer.parseInt(read.substring(0, space));
        this.start = Integer.parseInt(read.substring(space + 1)) + 1;
        this.readIndex(this.generation, this.places);
        this.bytes = sumBytes(this.places);
    }

    private void mergeHead() throws IOException {
        Path head = this.folder.resolve(this.kind + ".head");
        if (Files.notExists(head)) {
            return;
        }
        try {
            String read = Files.readString(head, StandardCharsets.UTF_8).trim();
            int latest = Integer.parseInt(read.substring(0, read.indexOf(' ')));
            if (latest != this.generation) {
                Map<CacheKey, Place> theirs = new HashMap<>();
                this.readIndex(latest, theirs);
                theirs.forEach(this.places::putIfAbsent);
                this.generation = Math.max(this.generation, latest);
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
            into.put(key, new Place(read.getInt(), read.getLong(), read.getInt(), read.getInt()));
        }
    }

    private byte @Nullable [] read(Place place, byte @Nullable [] dictionary) {
        byte[] raw = this.readRaw(place);
        if (raw == null) {
            return null;
        }
        try {
            ByteBuffer at = ByteBuffer.wrap(raw);
            int crc = at.getInt();
            byte flags = at.get();
            byte[] packed = new byte[place.length()];
            at.get(packed);
            boolean valueChecked = (flags & VALUE_CHECKED) != 0;
            if (!valueChecked && check(packed) != crc) {
                return null;
            }
            byte[] value = expand(packed, (flags & WITH_DICTIONARY) != 0 ? dictionary : null);
            return value == null || (valueChecked && check(value) != crc) ? null : value;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void write(boolean empty) throws IOException {
        int next = this.generation + 1;
        Map<CacheKey, Place> written = new HashMap<>(this.places);
        long total = 0;
        for (Place place : written.values()) {
            total += place.length() + 5;
        }
        for (byte[] packed : this.pending.values()) {
            total += packed.length + 5;
        }
        boolean full = total > this.capBytes;
        int oldest = this.start - STALE_STARTS;
        boolean stale = written.values().stream().anyMatch(place -> place.lastUse() < oldest);
        boolean compact = empty || this.lost || full || stale
                || written.values().stream().map(Place::pack).distinct().count() >= MOST_PACKS;
        long limit = full ? this.capBytes * 3 / 4 : Long.MAX_VALUE;
        ByteArrayOutputStream pack = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(4).putInt(PACK_MAGIC);
        pack.write(header.array());
        if (compact) {
            List<Map.Entry<CacheKey, Place>> byUse = new ArrayList<>(written.entrySet());
            byUse.sort(Comparator.comparingInt((Map.Entry<CacheKey, Place> entry) -> entry.getValue().lastUse())
                    .reversed());
            written.clear();
            long copied = 0;
            for (Map.Entry<CacheKey, Place> entry : byUse) {
                if (entry.getValue().lastUse() < oldest) {
                    continue;
                }
                byte[] raw = this.readRaw(entry.getValue());
                if (raw == null || copied + raw.length > limit) {
                    continue;
                }
                copied += raw.length;
                written.put(entry.getKey(), new Place(next, pack.size(), raw.length - 5, entry.getValue().lastUse()));
                pack.write(raw);
            }
        }
        for (Map.Entry<CacheKey, byte[]> entry : this.pending.entrySet()) {
            byte[] packed = entry.getValue();
            ByteBuffer entryHeader = ByteBuffer.allocate(5).putInt(this.pendingChecks.getOrDefault(entry.getKey(), 0))
                    .put(this.pendingFlags.getOrDefault(entry.getKey(), (byte) 0));
            written.put(entry.getKey(), new Place(next, pack.size(), packed.length, this.start));
            pack.write(entryHeader.array());
            pack.write(packed);
        }

        if (compact || !this.pending.isEmpty()) {
            Files.write(this.folder.resolve(this.kind + "." + next + ".pack"), pack.toByteArray());
        }
        ByteBuffer index = ByteBuffer.allocate(HEADER + written.size() * RECORD);
        index.putInt(INDEX_MAGIC).putInt(written.size());
        new TreeMap<>(written).forEach((key, place) -> index.putLong(key.high()).putLong(key.low())
                .putInt(place.pack()).putLong(place.offset()).putInt(place.length()).putInt(place.lastUse()));

        AtomicFiles.write(this.folder.resolve(this.kind + "." + next + ".idx"), index.array());
        Path head = this.folder.resolve(this.kind + ".head");
        AtomicFiles.writeString(head, next + " " + this.start);
        int before = this.generation;
        this.generation = next;
        this.places.clear();
        this.places.putAll(written);
        this.bytes = sumBytes(written);
        this.lost = false;
        this.dropUnused(before);
    }

    private byte @Nullable [] readRaw(Place place) {
        try {
            FileChannel pack = this.packs.get(place.pack());
            if (pack == null) {
                pack = FileChannel.open(this.folder.resolve(this.kind + "." + place.pack() + ".pack"),
                        StandardOpenOption.READ);
                this.packs.put(place.pack(), pack);
            }
            ByteBuffer raw = ByteBuffer.allocate(place.length() + 5);
            long at = place.offset();
            while (raw.hasRemaining()) {
                int read = pack.read(raw, at + raw.position());
                if (read < 0) {
                    return null;
                }
            }
            return raw.array();
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
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
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
