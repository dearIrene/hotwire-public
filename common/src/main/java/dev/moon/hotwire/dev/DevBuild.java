package dev.moon.hotwire.dev;

import dev.moon.hotwire.config.AtomicFiles;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;

public final class DevBuild {
    public record Diff(SortedSet<String> changedClasses, SortedSet<String> addedClasses,
            SortedSet<String> removedClasses, SortedSet<String> changedFiles) {
        public boolean isEmpty() {
            return this.changedClasses.isEmpty() && this.addedClasses.isEmpty() && this.removedClasses.isEmpty()
                    && this.changedFiles.isEmpty();
        }

        public boolean hasClasses() {
            return !this.changedClasses.isEmpty() || !this.addedClasses.isEmpty() || !this.removedClasses.isEmpty();
        }
    }

    private record FileSum(long size, long modified, long crc, @Nullable String hash) {
    }

    private record FileOnDisk(Path file, long size, long modified, @Nullable String entry) {
    }

    private static final String INDEX = ".idx";
    private static final String STORE = "files";

    private static final String HEADER = "# 2 ";

    private static final int FORCED_WRITES = 64;

    private static final Map<Path, Set<String>> PRESENT = new ConcurrentHashMap<>();

    private static final Map<Path, Set<String>> SOUND = new ConcurrentHashMap<>();
    private static final AtomicLong PARTS = new AtomicLong();
    private final Path jar;
    private final Map<String, FileSum> files;
    private final long added;

    private DevBuild(Path jar, Map<String, FileSum> files, long added) {
        this.jar = jar;
        this.files = files;
        this.added = added;
    }

    public Path getJar() {
        return this.jar;
    }

    public long findNewestClass() {
        long newest = 0;
        for (Map.Entry<String, FileSum> file : this.files.entrySet()) {
            if (file.getKey().endsWith(".class")) {
                newest = Math.max(newest, file.getValue().modified());
            }
        }
        return newest;
    }

    public long getAdded() {
        return this.added;
    }

    public byte @Nullable [] readClass(String binary) {
        String name = binary.replace('.', '/') + ".class";
        return this.readEntries(List.of(name)).get(name);
    }

    public Map<String, byte[]> readEntries(Collection<String> names) {
        Map<String, byte[]> read = new TreeMap<>();
        if (names.isEmpty()) {
            return read;
        }
        if (Files.isRegularFile(this.jar)) {
            try (ZipFile zip = new ZipFile(this.jar.toFile())) {
                for (String name : names) {
                    ZipEntry entry = zip.getEntry(name);
                    if (entry != null) {
                        read.put(name, zip.getInputStream(entry).readAllBytes());
                    }
                }
                return read;
            } catch (IOException e) {
                read.clear();
            }
        }
        for (String name : names) {
            FileSum sum = this.files.get(name);
            if (sum != null && sum.hash() != null) {
                try {
                    read.put(name, readStored(this.findStore(), sum));
                } catch (IOException e) {
                }
            }
        }
        return read;
    }

    public boolean hasClass(String binary) {
        return this.files.containsKey(binary.replace('.', '/') + ".class");
    }

    public Set<String> getClassNames() {
        Set<String> names = new HashSet<>();
        for (String entry : this.files.keySet()) {
            if (entry.endsWith(".class") && !entry.endsWith("module-info.class")) {
                names.add(entry.substring(0, entry.length() - ".class".length()).replace('/', '.'));
            }
        }
        return names;
    }

    public static DevBuild take(List<Path> sources, Path jar, @Nullable DevBuild previous) throws IOException {
        Map<String, FileOnDisk> found = find(sources);
        if (previous != null && previous.isSame(found)) {
            previous.ensureJar();
            return previous;
        }
        Files.createDirectories(jar.getParent());
        Path store = storeOf(jar);
        Path part = jar.resolveSibling(jar.getFileName() + ".part");
        Map<String, FileSum> files = new TreeMap<>();
        Map<Path, ZipFile> opened = new HashMap<>();
        Map<String, byte[]> toStore = new HashMap<>();
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(part), 1 << 16);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, FileOnDisk> entry : found.entrySet()) {
                FileOnDisk file = entry.getValue();
                byte[] bytes = read(file, opened);
                FileSum before = previous == null ? null : previous.files.get(entry.getKey());
                boolean same = before != null && before.size() == bytes.length
                        && before.modified() == file.modified();
                long crc = same ? before.crc() : findCrc(bytes);
                String hash = same && before.hash() != null ? before.hash() : findHash(bytes);
                if (!isKnown(store, hash)) {
                    toStore.put(hash, bytes);
                }

                putStored(zip, entry.getKey(), bytes, crc);
                files.put(entry.getKey(), new FileSum(bytes.length, file.modified(), crc, hash));
            }
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        } finally {
            for (ZipFile open : opened.values()) {
                open.close();
            }
        }
        long added = storeAll(store, toStore);
        AtomicFiles.moveInto(part, jar);
        DevBuild build = new DevBuild(jar, files, added);
        build.writeIndex();
        return build;
    }

    public boolean isIntact() {
        if (!Files.isRegularFile(this.jar)) {
            return this.isStored();
        }
        try (ZipFile zip = new ZipFile(this.jar.toFile())) {
            if (zip.size() != this.files.size()) {
                return false;
            }
            for (Map.Entry<String, FileSum> file : this.files.entrySet()) {
                ZipEntry entry = zip.getEntry(file.getKey());
                if (entry == null || entry.getSize() != file.getValue().size()
                        || entry.getCrc() != file.getValue().crc()) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public Path ensureJar() throws IOException {
        if (Files.isRegularFile(this.jar) && this.isIntact()) {
            return this.jar;
        }
        Path store = this.findStore();
        Path part = this.jar.resolveSibling(this.jar.getFileName() + ".part");
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(part), 1 << 16);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, FileSum> file : this.files.entrySet()) {
                putStored(zip, file.getKey(), readStored(store, file.getValue()), file.getValue().crc());
            }
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        AtomicFiles.moveInto(part, this.jar);
        return this.jar;
    }

    public boolean dropJar() {
        if (!Files.isRegularFile(this.jar)) {
            return true;
        }
        Path store = this.findStore();
        try {
            boolean rewrite = false;
            Map<String, byte[]> toStore = new HashMap<>();
            try (ZipFile zip = new ZipFile(this.jar.toFile())) {
                for (Map.Entry<String, FileSum> file : this.files.entrySet()) {
                    FileSum sum = file.getValue();
                    if (isSound(store, sum)) {
                        continue;
                    }
                    ZipEntry entry = zip.getEntry(file.getKey());
                    if (entry == null) {
                        return false;
                    }
                    byte[] bytes = zip.getInputStream(entry).readAllBytes();
                    if (bytes.length != sum.size() || findCrc(bytes) != sum.crc()) {
                        return false;
                    }
                    String hash = findHash(bytes);
                    if (!hash.equals(sum.hash())) {
                        file.setValue(new FileSum(sum.size(), sum.modified(), sum.crc(), hash));
                        rewrite = true;
                    }
                    toStore.put(hash, bytes);
                }
            } catch (NoSuchFileException e) {
                return false;
            }
            storeAll(store, toStore);
            if (rewrite) {
                this.writeIndex();
            }
            Files.deleteIfExists(this.jar);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean hasEntry(String name) {
        return this.files.containsKey(name);
    }

    public static DevBuild combine(DevBuild start, Map<String, DevBuild> from, Set<String> gone, Path jar)
            throws IOException {
        Map<String, DevBuild> sources = new TreeMap<>();
        for (String name : start.files.keySet()) {
            sources.put(name, start);
        }
        sources.putAll(from);
        sources.keySet().removeAll(gone);
        Map<DevBuild, List<String>> byBuild = new HashMap<>();
        sources.forEach((name, build) -> byBuild.computeIfAbsent(build, ignored -> new ArrayList<>()).add(name));
        Map<String, byte[]> bytesOf = new HashMap<>();
        for (Map.Entry<DevBuild, List<String>> one : byBuild.entrySet()) {
            Map<String, byte[]> read = one.getKey().readEntries(one.getValue());
            for (String name : one.getValue()) {
                byte[] bytes = read.get(name);
                if (bytes == null) {
                    throw new IOException(one.getKey().jar.getFileName() + " has no " + name);
                }
                bytesOf.put(name, bytes);
            }
        }
        Files.createDirectories(jar.getParent());
        Path store = storeOf(jar);
        Path part = jar.resolveSibling(jar.getFileName() + ".part");
        Map<String, FileSum> files = new TreeMap<>();
        Map<String, byte[]> toStore = new HashMap<>();
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(part), 1 << 16);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, DevBuild> source : sources.entrySet()) {
                String name = source.getKey();
                FileSum sum = source.getValue().files.get(name);
                byte[] bytes = bytesOf.get(name);
                if (sum == null || bytes == null) {
                    throw new IOException(source.getValue().jar.getFileName() + " has no " + name);
                }
                String hash = sum.hash() != null ? sum.hash() : findHash(bytes);
                if (!isKnown(store, hash)) {
                    toStore.put(hash, bytes);
                }
                putStored(zip, name, bytes, sum.crc());
                files.put(name, new FileSum(sum.size(), sum.modified(), sum.crc(), hash));
            }
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        long added = storeAll(store, toStore);
        AtomicFiles.moveInto(part, jar);
        DevBuild build = new DevBuild(jar, files, added);
        build.writeIndex();
        return build;
    }

    public static void collect(Path modFolder) throws IOException {
        Path store = modFolder.resolve(STORE);
        if (!Files.isDirectory(store)) {
            return;
        }
        Set<String> listed = new HashSet<>();
        for (Path folder : List.of(modFolder, modFolder.resolve("cleared"))) {
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (DirectoryStream<Path> indexes = Files.newDirectoryStream(folder, "*" + INDEX)) {
                for (Path index : indexes) {
                    DevBuild build = readIndexAt(index, folder.resolve(index.getFileName().toString()
                            .substring(0, index.getFileName().toString().length() - INDEX.length())));
                    if (build == null) {
                        return;
                    }
                    for (FileSum sum : build.files.values()) {
                        if (sum.hash() == null) {
                            return;
                        }
                        listed.add(sum.hash());
                    }
                }
            }
        }
        PRESENT.remove(store);
        Files.walkFileTree(store, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (!listed.contains(file.getFileName().toString())) {
                    Files.deleteIfExists(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private Path findStore() {
        return storeOf(this.jar);
    }

    private static Path storeOf(Path jar) {
        Path folder = jar.toAbsolutePath().getParent();
        if ("cleared".equals(String.valueOf(folder.getFileName()))) {
            folder = folder.getParent();
        }
        return folder.resolve(STORE);
    }

    private static boolean hasSize(Path file, long size) {
        try {
            return Files.size(file) == size;
        } catch (IOException e) {
            return false;
        }
    }

    private static Path findBlob(Path store, String hash) {
        return store.resolve(hash.substring(0, 2)).resolve(hash);
    }

    private static boolean isKnown(Path store, String hash) {
        Set<String> known = PRESENT.computeIfAbsent(store, ignored -> ConcurrentHashMap.newKeySet());
        if (known.contains(hash)) {
            return true;
        }
        if (Files.isRegularFile(findBlob(store, hash))) {
            known.add(hash);
            return true;
        }
        return false;
    }

    private boolean isStored() {
        Path store = this.findStore();
        for (FileSum sum : this.files.values()) {
            if (sum.hash() == null || !hasSize(findBlob(store, sum.hash()), sum.size())) {
                return false;
            }
        }
        return true;
    }

    private static long storeAll(Path store, Map<String, byte[]> toStore) throws IOException {
        boolean force = toStore.size() <= FORCED_WRITES;
        long written = 0;
        Set<String> known = PRESENT.computeIfAbsent(store, ignored -> ConcurrentHashMap.newKeySet());
        Set<String> sound = SOUND.computeIfAbsent(store, ignored -> ConcurrentHashMap.newKeySet());
        for (Map.Entry<String, byte[]> one : toStore.entrySet()) {
            Path blob = findBlob(store, one.getKey());
            if (Files.isRegularFile(blob) && Files.size(blob) == one.getValue().length) {
                known.add(one.getKey());
                continue;
            }
            Files.createDirectories(blob.getParent());
            Path part = blob.resolveSibling(one.getKey() + "." + PARTS.incrementAndGet() + ".part");
            try (FileChannel channel = FileChannel.open(part, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(one.getValue());
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                if (force) {
                    channel.force(true);
                }
            } catch (IOException e) {
                Files.deleteIfExists(part);
                throw e;
            }
            AtomicFiles.moveInto(part, blob);
            known.add(one.getKey());
            sound.add(one.getKey());
            written += one.getValue().length;
        }
        return written;
    }

    private static byte[] readStored(Path store, FileSum sum) throws IOException {
        String hash = sum.hash();
        if (hash == null) {
            throw new IOException("a file was never stored");
        }
        Path blob = findBlob(store, hash);
        byte[] bytes = Files.readAllBytes(blob);
        if (bytes.length != sum.size() || !findHash(bytes).equals(hash)) {
            Files.deleteIfExists(blob);
            PRESENT.getOrDefault(store, Set.of()).remove(hash);
            throw new IOException(hash + " changed on disk");
        }
        return bytes;
    }

    private static boolean isSound(Path store, FileSum sum) {
        Set<String> sound = SOUND.computeIfAbsent(store, ignored -> ConcurrentHashMap.newKeySet());
        if (sum.hash() == null) {
            return false;
        }
        if (sound.contains(sum.hash())) {
            return true;
        }
        try {
            readStored(store, sum);
        } catch (IOException e) {
            return false;
        }
        sound.add(sum.hash());
        return true;
    }

    private static void putStored(ZipOutputStream zip, String name, byte[] bytes, long crc) throws IOException {
        ZipEntry zipped = new ZipEntry(name);
        zipped.setMethod(ZipEntry.STORED);
        zipped.setSize(bytes.length);
        zipped.setCompressedSize(bytes.length);
        zipped.setCrc(crc);
        zip.putNextEntry(zipped);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static byte[] read(FileOnDisk file, Map<Path, ZipFile> opened) throws IOException {
        String name = file.entry();
        if (name == null) {
            return Files.readAllBytes(file.file());
        }
        ZipFile zip = opened.get(file.file());
        if (zip == null) {
            zip = new ZipFile(file.file().toFile());
            opened.put(file.file(), zip);
        }
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            throw new IOException(file.file() + " lost " + name + " while it was read");
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static long findCrc(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    static String findHash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM has SHA-256", e);
        }
    }

    private static Map<String, FileOnDisk> find(List<Path> sources) throws IOException {
        Map<String, FileOnDisk> found = new TreeMap<>();
        for (Path folder : sources) {
            if (Files.isRegularFile(folder)) {
                findInJar(folder, found);
                continue;
            }
            if (!Files.isDirectory(folder)) {
                continue;
            }
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile()) {
                        long modified = attributes.lastModifiedTime().toMillis();
                        found.putIfAbsent(folder.relativize(file).toString().replace('\\', '/'),
                                new FileOnDisk(file, attributes.size(), modified, null));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return found;
    }

    private static void findInJar(Path jar, Map<String, FileOnDisk> found) throws IOException {
        long modified = Files.getLastModifiedTime(jar).toMillis();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (!entry.isDirectory()) {
                    String name = entry.getName();
                    found.putIfAbsent(name, new FileOnDisk(jar, entry.getSize(), modified, name));
                }
            }
        }
    }

    private boolean isSame(Map<String, FileOnDisk> found) {
        if (found.size() != this.files.size()) {
            return false;
        }
        for (Map.Entry<String, FileOnDisk> entry : found.entrySet()) {
            FileSum seen = this.files.get(entry.getKey());
            if (seen == null || seen.size() != entry.getValue().size()
                    || seen.modified() != entry.getValue().modified()) {
                return false;
            }
        }
        return true;
    }

    public void writeIndex() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(HEADER + this.added);
        this.files.forEach((name, seen) -> lines.add(seen.size() + " " + seen.modified() + " " + seen.crc() + " "
                + (seen.hash() == null ? "-" : seen.hash()) + " " + name));
        AtomicFiles.writeString(this.jar.resolveSibling(this.jar.getFileName() + INDEX), String.join("\n", lines));
    }

    public static @Nullable DevBuild readIndex(Path jar) {
        return readIndexAt(jar.resolveSibling(jar.getFileName() + INDEX), jar);
    }

    private static @Nullable DevBuild readIndexAt(Path index, Path jar) {
        if (!Files.isRegularFile(index)) {
            return null;
        }
        Map<String, FileSum> files = new TreeMap<>();
        long added = 0;
        try {
            List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8);
            boolean stored = !lines.isEmpty() && lines.getFirst().startsWith(HEADER);
            if (stored) {
                added = Long.parseLong(lines.getFirst().substring(HEADER.length()).trim());
            } else if (!Files.isRegularFile(jar)) {
                return null;
            }
            for (String line : stored ? lines.subList(1, lines.size()) : lines) {
                String[] parts = line.split(" ", stored ? 5 : 4);
                if (parts.length != (stored ? 5 : 4)) {
                    return null;
                }
                String hash = stored && !"-".equals(parts[3]) ? parts[3] : null;
                files.put(parts[stored ? 4 : 3], new FileSum(Long.parseLong(parts[0]), Long.parseLong(parts[1]),
                        Long.parseLong(parts[2]), hash));
            }
        } catch (IOException | NumberFormatException e) {
            return null;
        }
        return new DevBuild(jar, files, added);
    }

    public Diff diff(DevBuild newer) {
        SortedSet<String> changed = new TreeSet<>();
        SortedSet<String> added = new TreeSet<>();
        SortedSet<String> removed = new TreeSet<>();
        SortedSet<String> files = new TreeSet<>();
        newer.files.forEach((name, seen) -> {
            FileSum before = this.files.get(name);
            boolean hashed = before != null && before.hash() != null && seen.hash() != null;
            if (before != null && (hashed ? before.hash().equals(seen.hash())
                    : before.crc() == seen.crc() && before.size() == seen.size())) {
                return;
            }
            boolean isClass = name.endsWith(".class");
            if (!isClass) {
                files.add(name);
            } else if (before == null) {
                added.add(name);
            } else {
                changed.add(name);
            }
        });
        this.files.keySet().forEach(name -> {
            if (!newer.files.containsKey(name)) {
                if (name.endsWith(".class")) {
                    removed.add(name);
                } else {
                    files.add(name);
                }
            }
        });
        return new Diff(changed, added, removed, files);
    }
}
