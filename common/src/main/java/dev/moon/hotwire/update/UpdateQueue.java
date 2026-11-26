package dev.moon.hotwire.update;

import dev.moon.hotwire.config.AtomicFiles;
import dev.moon.hotwire.config.JsonFile;
import dev.moon.hotwire.jar.JarLookup;
import dev.moon.hotwire.jar.ModManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class UpdateQueue {
    static final int VERSION = 1;

    public record Entry(String modId, String name, String fromVersion, String toVersion, String runningJar,
            String runningSha256, String stagedJar, String stagedSha256, String reason, long queuedAtMillis) {
        Entry withReason(String why) {
            return new Entry(this.modId, this.name, this.fromVersion, this.toVersion, this.runningJar,
                    this.runningSha256, this.stagedJar, this.stagedSha256, why, this.queuedAtMillis);
        }
    }

    record ReplacedJar(String modId, String name, String version, String file) {
    }

    private static final class QueueFile {
        @SuppressWarnings("UnusedVariable")
        final int version = VERSION;
        List<Entry> entries = new ArrayList<>();
        List<ReplacedJar> replaced = new ArrayList<>();
    }

    private final JsonFile<QueueFile> file;
    private Runnable onAdded = () -> { };

    public UpdateQueue(Path path) {
        this.file = new JsonFile<>(path, QueueFile.class, QueueFile::new);
    }

    private QueueFile load() {
        QueueFile queued = this.file.load();
        queued.entries = queued.entries == null ? new ArrayList<>() : queued.entries;
        queued.replaced = queued.replaced == null ? new ArrayList<>() : queued.replaced;
        queued.entries.removeIf(entry -> entry == null || entry.modId() == null || entry.reason() == null);
        queued.replaced.removeIf(record -> record == null || record.file() == null);
        return queued;
    }

    public synchronized void setOnAdded(Runnable onAdded) {
        this.onAdded = onAdded;
    }

    public synchronized List<Entry> getEntries() {
        return List.copyOf(this.load().entries);
    }

    public static Path putInStaging(Path staging, Path jar, boolean linked) throws IOException {
        Files.createDirectories(staging);
        Path named = staging.resolve(String.valueOf(jar.getFileName()));
        if (named.toAbsolutePath().normalize().equals(jar.toAbsolutePath().normalize())) {
            return named;
        }

        ModManifest there = Files.isRegularFile(named) ? JarLookup.findManifest(named) : null;
        ModManifest coming = there == null ? null : JarLookup.findManifest(jar);
        Path staged = there != null && coming != null && !there.id().equals(coming.id())
                ? Files.createDirectories(staging.resolve(coming.id())).resolve(String.valueOf(jar.getFileName()))
                : named;
        Path part = staged.resolveSibling(staged.getFileName() + ".part");
        Files.deleteIfExists(part);
        try {
            if (linked) {
                Files.createLink(part, jar);
            } else {
                Files.copy(jar, part, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | UnsupportedOperationException e) {
            if (!linked) {
                throw e;
            }
            Files.copy(jar, part, StandardCopyOption.REPLACE_EXISTING);
        }
        AtomicFiles.moveInto(part, staged);
        return staged;
    }

    public void add(Entry entry) throws IOException {
        synchronized (this) {
            QueueFile queued = this.load();
            queued.entries.removeIf(other -> other.modId().equals(entry.modId()));
            queued.entries.add(entry);
            this.file.save(queued);
        }
        this.onAdded.run();
    }

    public synchronized void remove(String modId) throws IOException {
        QueueFile queued = this.load();
        queued.entries.removeIf(entry -> entry.modId().equals(modId));
        this.file.save(queued);
    }

    synchronized void replace(List<Entry> entries) throws IOException {
        QueueFile queued = this.load();
        queued.entries = new ArrayList<>(entries);
        this.file.save(queued);
    }

    synchronized void replaceAll(List<Entry> entries, List<ReplacedJar> records) throws IOException {
        QueueFile queued = this.load();
        queued.entries = new ArrayList<>(entries);
        queued.replaced = new ArrayList<>(records);
        this.file.save(queued);
    }

    synchronized List<ReplacedJar> getReplaced() {
        return List.copyOf(this.load().replaced);
    }

    synchronized void addReplaced(ReplacedJar record) throws IOException {
        QueueFile queued = this.load();
        queued.replaced.removeIf(other -> other.file().equals(record.file()));
        queued.replaced.add(record);
        this.file.save(queued);
    }

    synchronized void replaceReplaced(List<ReplacedJar> records) throws IOException {
        QueueFile queued = this.load();
        queued.replaced = new ArrayList<>(records);
        this.file.save(queued);
    }
}
