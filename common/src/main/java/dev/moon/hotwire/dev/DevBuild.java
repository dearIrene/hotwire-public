package dev.moon.hotwire.dev;

import dev.moon.hotwire.config.AtomicFiles;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
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

    private record FileSum(long size, long modified, long crc) {
    }

    private record FileOnDisk(Path file, long size, long modified, @Nullable String entry) {
    }

    private static final String INDEX = ".idx";
    private final Path jar;
    private final Map<String, FileSum> files;

    private DevBuild(Path jar, Map<String, FileSum> files) {
        this.jar = jar;
        this.files = files;
    }

    public Path getJar() {
        return this.jar;
    }

    public byte @Nullable [] readClass(String binary) {
        try (ZipFile zip = new ZipFile(this.jar.toFile())) {
            ZipEntry entry = zip.getEntry(binary.replace('.', '/') + ".class");
            return entry == null ? null : zip.getInputStream(entry).readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    public Map<String, byte[]> readEntries(Collection<String> names) {
        Map<String, byte[]> read = new TreeMap<>();
        if (names.isEmpty()) {
            return read;
        }
        try (ZipFile zip = new ZipFile(this.jar.toFile())) {
            for (String name : names) {
                ZipEntry entry = zip.getEntry(name);
                if (entry != null) {
                    read.put(name, zip.getInputStream(entry).readAllBytes());
                }
            }
        } catch (IOException e) {
            return read;
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
            return previous;
        }
        Files.createDirectories(jar.getParent());
        Path part = jar.resolveSibling(jar.getFileName() + ".part");
        Map<String, FileSum> files = new TreeMap<>();
        Map<Path, ZipFile> opened = new HashMap<>();
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(part), 1 << 16);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, FileOnDisk> entry : found.entrySet()) {
                FileOnDisk file = entry.getValue();
                byte[] bytes = read(file, opened);
                FileSum before = previous == null ? null : previous.files.get(entry.getKey());
                long crc = before != null && before.size() == bytes.length && before.modified() == file.modified()
                        ? before.crc() : findCrc(bytes);

                ZipEntry zipped = new ZipEntry(entry.getKey());
                zipped.setMethod(ZipEntry.STORED);
                zipped.setSize(bytes.length);
                zipped.setCompressedSize(bytes.length);
                zipped.setCrc(crc);
                zip.putNextEntry(zipped);
                zip.write(bytes);
                zip.closeEntry();
                files.put(entry.getKey(), new FileSum(bytes.length, file.modified(), crc));
            }
        } finally {
            for (ZipFile open : opened.values()) {
                open.close();
            }
        }
        AtomicFiles.moveInto(part, jar);
        return new DevBuild(jar, files);
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
        this.files.forEach((name, seen) -> lines.add(seen.size() + " " + seen.modified() + " " + seen.crc() + " "
                + name));
        AtomicFiles.writeString(this.jar.resolveSibling(this.jar.getFileName() + INDEX), String.join("\n", lines));
    }

    public static @Nullable DevBuild readIndex(Path jar) {
        Path index = jar.resolveSibling(jar.getFileName() + INDEX);
        if (!Files.isRegularFile(jar) || !Files.isRegularFile(index)) {
            return null;
        }
        Map<String, FileSum> files = new TreeMap<>();
        try {
            for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                String[] parts = line.split(" ", 4);
                if (parts.length != 4) {
                    return null;
                }
                files.put(parts[3], new FileSum(Long.parseLong(parts[0]), Long.parseLong(parts[1]),
                        Long.parseLong(parts[2])));
            }
        } catch (IOException | NumberFormatException e) {
            return null;
        }
        return new DevBuild(jar, files);
    }

    public Diff diff(DevBuild newer) {
        SortedSet<String> changed = new TreeSet<>();
        SortedSet<String> added = new TreeSet<>();
        SortedSet<String> removed = new TreeSet<>();
        SortedSet<String> files = new TreeSet<>();
        newer.files.forEach((name, seen) -> {
            FileSum before = this.files.get(name);
            if (before != null && before.crc() == seen.crc() && before.size() == seen.size()) {
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
