package dev.moon.hotwire.update;

import dev.moon.hotwire.config.JsonFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
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

    public UpdateQueue(Path path) {
        this.file = new JsonFile<>(path, QueueFile.class, QueueFile::new);
    }

    public synchronized List<Entry> getEntries() {
        return List.copyOf(this.file.load().entries);
    }

    public synchronized void add(Entry entry) throws IOException {
        QueueFile queued = this.file.load();
        queued.entries.removeIf(other -> other.modId().equals(entry.modId()));
        queued.entries.add(entry);
        this.file.save(queued);
    }

    public synchronized void remove(String modId) throws IOException {
        QueueFile queued = this.file.load();
        queued.entries.removeIf(entry -> entry.modId().equals(modId));
        this.file.save(queued);
    }

    synchronized void replace(List<Entry> entries) throws IOException {
        QueueFile queued = this.file.load();
        queued.entries = new ArrayList<>(entries);
        this.file.save(queued);
    }

    synchronized List<ReplacedJar> getReplaced() {
        return List.copyOf(this.file.load().replaced);
    }

    synchronized void addReplaced(ReplacedJar record) throws IOException {
        QueueFile queued = this.file.load();
        queued.replaced.removeIf(other -> other.file().equals(record.file()));
        queued.replaced.add(record);
        this.file.save(queued);
    }

    synchronized void removeReplaced(String file) throws IOException {
        QueueFile queued = this.file.load();
        queued.replaced.removeIf(record -> record.file().equals(file));
        this.file.save(queued);
    }

    synchronized void replaceReplaced(List<ReplacedJar> records) throws IOException {
        QueueFile queued = this.file.load();
        queued.replaced = new ArrayList<>(records);
        this.file.save(queued);
    }

    public static String hashSha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }
}
