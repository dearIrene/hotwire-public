package dev.moon.hotwire.switching;

import dev.moon.hotwire.config.JsonFile;
import dev.moon.hotwire.plan.SwitchPlan;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SwitchHistory {
    public enum Cause { PLAYER, MOD, PROFILE, ANTI_CRASH, UPDATE }

    public record Entry(boolean off, long atMillis, Cause cause, String by) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(SwitchHistory.class);
    private static final int MOST = 5;
    private static volatile SwitchHistory current = new SwitchHistory(null);

    private static final class HistoryJson {
        @Nullable Map<String, List<EntryJson>> mods = new TreeMap<>();
    }

    private static final class EntryJson {
        boolean off;
        long at;
        @Nullable String cause;
        @Nullable String by;
    }

    private final @Nullable JsonFile<HistoryJson> file;
    private final Map<String, List<Entry>> mods = new HashMap<>();

    public SwitchHistory(@Nullable Path path) {
        this.file = path == null ? null : new JsonFile<>(path, HistoryJson.class, HistoryJson::new);
        Map<String, List<EntryJson>> saved = this.file == null ? null : this.file.load().mods;
        if (saved != null) {
            saved.forEach((id, entries) -> entries.forEach(entry -> this.read(id, entry)));
        }
    }

    private void read(String id, @Nullable EntryJson entry) {
        for (Cause cause : Cause.values()) {
            if (entry != null && cause.name().equals(entry.cause)) {
                this.add(id, new Entry(entry.off, entry.at, cause, entry.by == null ? "" : entry.by));
            }
        }
    }

    public static SwitchHistory get() {
        return current;
    }

    public static void open(Path path) {
        current = new SwitchHistory(path);
    }

    public void recordDone(ModSwitcher.Result result, Cause cause, String by) {
        if (result instanceof ModSwitcher.Result.Done done) {
            this.record(done.plan().order(), done.plan().direction() == SwitchPlan.Direction.OFF,
                    System.currentTimeMillis(), cause, by);
        }
    }

    public synchronized void record(Collection<String> ids, boolean off, long atMillis, Cause cause, String by) {
        if (ids.isEmpty()) {
            return;
        }
        ids.forEach(id -> this.add(id, new Entry(off, atMillis, cause, by)));
        JsonFile<HistoryJson> onDisk = this.file;
        if (onDisk == null) {
            return;
        }
        HistoryJson written = new HistoryJson();
        Map<String, List<EntryJson>> lines = new TreeMap<>();
        this.mods.forEach((id, entries) -> lines.put(id, entries.stream().map(SwitchHistory::write).toList()));
        written.mods = lines;
        try {
            onDisk.save(written);
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the switch history could not be saved", e);
        }
    }

    private static EntryJson write(Entry entry) {
        EntryJson line = new EntryJson();
        line.off = entry.off();
        line.at = entry.atMillis();
        line.cause = entry.cause().name();
        line.by = entry.by();
        return line;
    }

    private synchronized void add(String id, Entry entry) {
        List<Entry> entries = this.mods.computeIfAbsent(id, unused -> new ArrayList<>());
        entries.add(entry);
        while (entries.size() > MOST) {
            entries.removeFirst();
        }
    }

    public synchronized List<Entry> find(String id) {
        return List.copyOf(this.mods.getOrDefault(id, List.of()).reversed());
    }
}
