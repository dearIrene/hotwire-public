package dev.moon.hotwire.jar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

public final class ModIndex {
    static final String DISABLED_SUFFIX = ".jar.disabled";

    private final Executor readers;
    private final Map<Path, CacheEntry> cache = new ConcurrentHashMap<>();

    public ModIndex(Executor readers) {
        this.readers = readers;
    }

    public record Entry(Path file, boolean disabled, ModJar jar) {
    }

    record Failure(Path file, ModJarException reason) {
    }

    public record Snapshot(List<Entry> mods, List<Failure> failures) {
        public Snapshot {
            mods = List.copyOf(mods);
            failures = List.copyOf(failures);
        }
    }

    private record Stamp(long size, long modified) {
    }

    private record CacheEntry(Stamp stamp, Result result) {
    }

    private sealed interface Result {
        record Read(ModJar jar) implements Result {
        }

        record Refused(ModJarException reason) implements Result {
        }
    }

    public Snapshot scan(Path folder) throws IOException {
        List<Path> files;
        try (Stream<Path> listed = Files.list(folder)) {
            files = listed.filter(ModIndex::isModFile).sorted().toList();
        }
        this.cache.keySet().removeIf(cached -> folder.equals(cached.getParent()) && !files.contains(cached));
        List<CompletableFuture<Result>> results = new ArrayList<>();
        for (Path file : files) {
            results.add(this.readResult(file));
        }
        List<Entry> mods = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            switch (join(results.get(i))) {
                case Result.Read read -> mods.add(new Entry(file, file.getFileName().toString().endsWith(
                        DISABLED_SUFFIX), read.jar()));
                case Result.Refused refused -> failures.add(new Failure(file, refused.reason()));
            }
        }
        return new Snapshot(mods, failures);
    }

    private static boolean isModFile(Path file) {
        String name = file.getFileName().toString();
        return Files.isRegularFile(file) && (name.endsWith(".jar") || name.endsWith(DISABLED_SUFFIX));
    }

    private CompletableFuture<Result> readResult(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        Stamp stamp = new Stamp(attributes.size(), attributes.lastModifiedTime().toMillis());
        CacheEntry cached = this.cache.get(file);
        if (cached != null && cached.stamp().equals(stamp)) {
            return CompletableFuture.completedFuture(cached.result());
        }
        return CompletableFuture.supplyAsync(() -> {
            Result result;
            try {
                result = new Result.Read(ModJarReader.read(file));
            } catch (ModJarException e) {
                result = new Result.Refused(e);
            }
            this.cache.put(file, new CacheEntry(stamp, result));
            return result;
        }, this.readers);
    }

    private static Result join(CompletableFuture<Result> result) throws IOException {
        try {
            return result.join();
        } catch (CompletionException e) {
            throw new IOException("reading the mods folder failed", e.getCause());
        }
    }
}
