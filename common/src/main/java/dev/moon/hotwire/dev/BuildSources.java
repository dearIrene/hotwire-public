package dev.moon.hotwire.dev;

import dev.moon.hotwire.config.AtomicFiles;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.jspecify.annotations.Nullable;

public final class BuildSources {
    private static final Set<String> SKIPPED = Set.of("build", "out", "bin", "run", ".gradle", ".git", ".idea",
            "node_modules");

    public record Entry(String hash, long size, long time, String path) {
    }

    private final Path store;
    private final Path project;
    private final List<Path> roots;

    private @Nullable Map<String, Entry> last;

    public BuildSources(Path store, Path project, List<Path> roots) {
        this.store = store;
        this.project = project.toAbsolutePath().normalize();
        this.roots = roots.stream().map(root -> root.toAbsolutePath().normalize()).toList();
    }

    public Path getProject() {
        return this.project;
    }

    public long take(int number) throws IOException {
        return this.takeAs(String.valueOf(number));
    }

    public synchronized long takeAs(String name) throws IOException {
        Map<String, Entry> before = this.last != null ? this.last : Map.of();
        Map<String, Entry> now = new TreeMap<>();
        long added = 0;
        for (Path root : this.roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            for (Map.Entry<Path, BasicFileAttributes> found : listFiles(root).entrySet()) {
                Path file = found.getKey();
                BasicFileAttributes attributes = found.getValue();
                String path = this.project.relativize(file).toString().replace('\\', '/');
                long time = attributes.lastModifiedTime().toMillis();
                Entry was = before.get(path);
                if (was != null && was.size() == attributes.size() && was.time() == time) {
                    now.put(path, was);
                    continue;
                }
                byte[] bytes;
                try {
                    bytes = Files.readAllBytes(file);
                } catch (NoSuchFileException e) {
                    continue;
                }
                String hash = hash(bytes);
                added += this.save(hash, bytes);
                now.put(path, new Entry(hash, bytes.length, time, path));
            }
        }
        List<String> lines = new ArrayList<>();
        for (Entry entry : now.values()) {
            lines.add(entry.hash() + "\t" + entry.size() + "\t" + entry.time() + "\t" + entry.path());
        }
        Path list = this.store.resolve("builds").resolve(name + ".txt");
        Files.createDirectories(list.getParent());
        AtomicFiles.writeString(list, String.join("\n", lines) + "\n" + "added\t" + added);
        this.last = now;
        return added;
    }

    public synchronized List<String> adopt(String listed, int number, long builtAt) throws IOException {
        Path from = this.store.resolve("builds").resolve(listed + ".txt");
        List<String> lines = new ArrayList<>(Files.readAllLines(from, StandardCharsets.UTF_8));
        List<String> stale = new ArrayList<>();
        for (Entry entry : this.read(listed).values()) {
            if (builtAt > 0 && entry.time() > builtAt && entry.path().endsWith(".java")) {
                stale.add(entry.path());
                lines.add("stale\t" + entry.path());
            }
        }
        AtomicFiles.writeString(this.store.resolve("builds").resolve(number + ".txt"), String.join("\n", lines));
        Files.deleteIfExists(from);
        return stale;
    }

    public Set<String> readStale(String name) throws IOException {
        Path list = this.store.resolve("builds").resolve(name + ".txt");
        Set<String> stale = new HashSet<>();
        if (Files.isRegularFile(list)) {
            for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
                if (line.startsWith("stale\t")) {
                    stale.add(line.substring("stale\t".length()));
                }
            }
        }
        return stale;
    }

    public Map<String, Entry> read(int number) throws IOException {
        return this.read(String.valueOf(number));
    }

    public Map<String, Entry> read(String name) throws IOException {
        Path list = this.store.resolve("builds").resolve(name + ".txt");
        Map<String, Entry> files = new LinkedHashMap<>();
        if (!Files.isRegularFile(list)) {
            return files;
        }
        for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
            String[] parts = line.split("\t", 4);
            if (parts.length == 4) {
                files.put(parts[3], new Entry(parts[0], Long.parseLong(parts[1]), Long.parseLong(parts[2]),
                        parts[3]));
            }
        }
        return files;
    }

    public long readAdded(int number) throws IOException {
        Path list = this.store.resolve("builds").resolve(number + ".txt");
        if (!Files.isRegularFile(list)) {
            return 0;
        }
        for (String line : Files.readAllLines(list, StandardCharsets.UTF_8).reversed()) {
            if (line.startsWith("added\t")) {
                return Long.parseLong(line.substring(6));
            }
        }
        return 0;
    }

    public byte[] readContents(String hash) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(this.store.resolve("objects")
                .resolve(hash + ".gz")))) {
            return in.readAllBytes();
        }
    }

    public boolean isSame(String a, String b) throws IOException {
        Map<String, Entry> first = this.read(a);
        Map<String, Entry> second = this.read(b);
        if (!first.keySet().equals(second.keySet())) {
            return false;
        }
        for (Map.Entry<String, Entry> file : first.entrySet()) {
            if (!file.getValue().hash().equals(second.get(file.getKey()).hash())) {
                return false;
            }
        }
        return true;
    }

    public void delete(String name) throws IOException {
        Files.deleteIfExists(this.store.resolve("builds").resolve(name + ".txt"));
    }

    public synchronized List<String> restore(String name) throws IOException {
        Map<String, Entry> target = this.read(name);
        if (target.isEmpty()) {
            throw new IOException("no sources were kept as " + name);
        }
        Set<String> seen = new HashSet<>();
        Path builds = this.store.resolve("builds");
        try (Stream<Path> lists = Files.list(builds)) {
            for (Path list : lists.toList()) {
                for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
                    String[] parts = line.split("\t", 4);
                    if (parts.length == 4) {
                        seen.add(parts[3]);
                    }
                }
            }
        }

        Set<String> stale = this.readStale(name);

        Map<String, byte[]> toWrite = new LinkedHashMap<>();
        for (Entry entry : target.values()) {
            Path file = this.project.resolve(entry.path());
            if (stale.contains(entry.path())
                    || (Files.isRegularFile(file) && hash(Files.readAllBytes(file)).equals(entry.hash()))) {
                continue;
            }
            byte[] bytes;
            try {
                bytes = this.readContents(entry.hash());
            } catch (IOException e) {
                bytes = new byte[0];
            }
            if (!hash(bytes).equals(entry.hash())) {
                Files.deleteIfExists(this.store.resolve("objects").resolve(entry.hash() + ".gz"));
                this.last = null;
                throw new IOException("the kept copy of " + entry.path() + " is damaged");
            }
            toWrite.put(entry.path(), bytes);
        }
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, byte[]> one : toWrite.entrySet()) {
            Path file = this.project.resolve(one.getKey());
            Files.createDirectories(file.getParent());
            Path part = file.resolveSibling(file.getFileName() + ".hotwire-part");
            try {
                Files.write(part, one.getValue());
                AtomicFiles.moveInto(part, file);
            } finally {
                Files.deleteIfExists(part);
            }
            changed.add(one.getKey());
        }
        for (String path : seen) {
            Path file = this.project.resolve(path);
            if (!target.containsKey(path) && Files.isRegularFile(file)) {
                Files.delete(file);
                changed.add(path);
            }
        }
        this.last = null;
        return changed;
    }

    public synchronized void hide(int number) throws IOException {
        move(this.store.resolve("builds").resolve(number + ".txt"), this.store.resolve("cleared")
                .resolve(number + ".txt"));
    }

    public synchronized void show(int number) throws IOException {
        move(this.store.resolve("cleared").resolve(number + ".txt"), this.store.resolve("builds")
                .resolve(number + ".txt"));
    }

    public synchronized long deleteCleared() throws IOException {
        Path cleared = this.store.resolve("cleared");
        if (Files.isDirectory(cleared)) {
            try (Stream<Path> lists = Files.list(cleared)) {
                for (Path list : lists.toList()) {
                    Files.delete(list);
                }
            }
        }
        return this.collect();
    }

    private static void move(Path from, Path to) throws IOException {
        if (Files.isRegularFile(from)) {
            Files.createDirectories(to.getParent());
            AtomicFiles.moveInto(from, to);
        }
    }

    public synchronized long forget(int number) throws IOException {
        Files.deleteIfExists(this.store.resolve("builds").resolve(number + ".txt"));
        return this.collect();
    }

    private long collect() throws IOException {
        Set<String> named = new HashSet<>();
        for (String folder : List.of("builds", "cleared")) {
            Path lists = this.store.resolve(folder);
            if (!Files.isDirectory(lists)) {
                continue;
            }
            try (Stream<Path> each = Files.list(lists)) {
                for (Path list : each.toList()) {
                    for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
                        int tab = line.indexOf('\t');
                        if (tab > 0 && !line.startsWith("added\t")) {
                            named.add(line.substring(0, tab));
                        }
                    }
                }
            }
        }
        long freed = 0;
        Path objects = this.store.resolve("objects");
        if (Files.isDirectory(objects)) {
            try (Stream<Path> stored = Files.list(objects)) {
                for (Path object : stored.toList()) {
                    if (!named.contains(object.getFileName().toString().replaceFirst("\\.gz$", ""))) {
                        freed += Files.size(object);
                        Files.delete(object);
                    }
                }
            }
        }
        return freed;
    }

    private long save(String hash, byte[] bytes) throws IOException {
        Path object = this.store.resolve("objects").resolve(hash + ".gz");
        if (Files.exists(object)) {
            return 0;
        }
        Files.createDirectories(object.getParent());
        Path part = object.resolveSibling(hash + ".part");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(part))) {
            out.write(bytes);
        }
        AtomicFiles.moveInto(part, object);
        return Files.size(object);
    }

    private static Map<Path, BasicFileAttributes> listFiles(Path root) throws IOException {
        Map<Path, BasicFileAttributes> files = new LinkedHashMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path folder, BasicFileAttributes attributes) {
                return !folder.equals(root) && SKIPPED.contains(String.valueOf(folder.getFileName()))
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && !SKIPPED.contains(String.valueOf(file.getFileName()))) {
                    files.put(file, attributes);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
