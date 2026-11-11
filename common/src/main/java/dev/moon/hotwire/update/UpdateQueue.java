package dev.moon.hotwire.update;

import dev.moon.hotwire.config.JsonFile;
import java.io.IOException;
import java.nio.file.Path;
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

    public synchronized void add(Entry entry) throws IOException {
        QueueFile queued = this.load();
        queued.entries.removeIf(other -> other.modId().equals(entry.modId()));
        queued.entries.add(entry);
        this.file.save(queued);
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
