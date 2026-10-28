package dev.moon.hotwire.update;

import dev.moon.hotwire.jar.FileHashes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class PendingChanges {
    static final int OLD_VERSIONS = 2;

    private final UpdateQueue queue;
    private final UpdateFolders folders;

    public PendingChanges(UpdateQueue queue, UpdateFolders folders) {
        this.queue = queue;
        this.folders = folders;
    }

    public List<PendingChange> list() {
        List<PendingChange> out = new ArrayList<>();
        Set<String> queued = new HashSet<>();
        for (UpdateQueue.Entry entry : this.queue.getEntries()) {
            out.add(new PendingChange.Update(entry.modId(), entry.name(), entry.fromVersion(), entry.toVersion(),
                    entry.runningJar(), String.valueOf(Path.of(entry.stagedJar()).getFileName()), entry.reason(),
                    entry.queuedAtMillis()));
            queued.add(entry.modId());
        }
        for (UpdateQueue.ReplacedJar retained : this.findNewestReplaced().values()) {
            if (!queued.contains(retained.modId()) && Files.exists(this.folders.replaced().resolve(retained.file()))) {
                out.add(new PendingChange.Revertable(retained.modId(), retained.name(), retained.version()));
            }
        }
        return out;
    }

    public void cancel(String modId) throws IOException {
        for (UpdateQueue.Entry entry : this.queue.getEntries()) {
            if (!entry.modId().equals(modId)) {
                continue;
            }
            Path staged = Path.of(entry.stagedJar()).toAbsolutePath();
            if (staged.startsWith(this.folders.staging().toAbsolutePath())) {
                Files.deleteIfExists(staged);
            }
        }
        this.queue.remove(modId);
    }

    public void revert(String modId, Path runningJar, String runningVersion, long nowMillis) throws IOException {
        UpdateQueue.ReplacedJar retained = this.findNewestReplaced().get(modId);
        if (retained == null) {
            throw new IllegalArgumentException("No earlier version of " + modId + " is kept.");
        }
        Path old = this.folders.replaced().resolve(retained.file());
        this.queue.add(new UpdateQueue.Entry(modId, retained.name(), runningVersion, retained.version(),
                String.valueOf(runningJar.getFileName()), FileHashes.findSha256(runningJar),
                old.toAbsolutePath().toString(), FileHashes.findSha256(old), "", nowMillis));
    }

    public void ignoreEarlier() throws IOException {
        this.queue.replaceReplaced(List.of());
    }

    public void cleanAtStart() throws IOException {
        Set<Path> wanted = new HashSet<>();
        for (UpdateQueue.Entry entry : this.queue.getEntries()) {
            wanted.add(Path.of(entry.stagedJar()).toAbsolutePath());
        }
        for (Path file : listFiles(this.folders.staging())) {
            if (!wanted.contains(file.toAbsolutePath())) {
                Files.delete(file);
            }
        }
        for (Path file : listFiles(this.folders.swapper())) {
            Files.delete(file);
        }
        Files.deleteIfExists(this.folders.swapper());
        trimReplaced(this.queue, this.folders.replaced());
    }

    static void trimReplaced(UpdateQueue queue, Path replaced) throws IOException {
        List<UpdateQueue.ReplacedJar> records = queue.getReplaced();
        List<UpdateQueue.ReplacedJar> retained = new ArrayList<>();
        Map<String, Integer> perMod = new HashMap<>();
        for (int at = records.size() - 1; at >= 0; at--) {
            UpdateQueue.ReplacedJar record = records.get(at);
            Path file = replaced.resolve(record.file());
            int seen = perMod.merge(record.modId(), 1, Integer::sum);
            if (seen <= OLD_VERSIONS && Files.exists(file)) {
                retained.add(0, record);
            } else {
                Files.deleteIfExists(file);
            }
        }
        queue.replaceReplaced(retained);
    }

    private Map<String, UpdateQueue.ReplacedJar> findNewestReplaced() {
        Map<String, UpdateQueue.ReplacedJar> newest = new LinkedHashMap<>();
        for (UpdateQueue.ReplacedJar record : this.queue.getReplaced()) {
            newest.put(record.modId(), record);
        }
        return newest;
    }

    private static List<Path> listFiles(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> listed = Files.list(folder)) {
            return listed.filter(Files::isRegularFile).toList();
        }
    }
}
