package dev.moon.hotwire.switching;

import dev.moon.hotwire.config.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BestTimes {
    private static final Logger LOGGER = LoggerFactory.getLogger(BestTimes.class);

    private static final double MARGIN = 0.95;
    private static volatile BestTimes current = new BestTimes(null);
    private static volatile Consumer<Beaten> told = beaten -> { };

    public record Best(long millis, int together) {
    }

    public record Beaten(List<String> asked, List<String> ids, boolean off, long millis, long before) {
    }

    private final Map<String, Long> best = new TreeMap<>();
    private final @Nullable Path path;

    public BestTimes(@Nullable Path path) {
        this.path = path;
        if (path != null && Files.isRegularFile(path)) {
            try {
                for (String line : Files.readAllLines(path)) {
                    int cut = line.lastIndexOf(' ');
                    if (cut > 0) {
                        this.best.put(line.substring(0, cut), Long.parseLong(line.substring(cut + 1)));
                    }
                }
            } catch (IOException | NumberFormatException e) {
                LOGGER.warn("[Hotwire] the best times could not be read, so they start again", e);
                this.best.clear();
            }
        }
    }

    public static BestTimes get() {
        return current;
    }

    public static void open(Path path) {
        current = new BestTimes(path);
    }

    public static void listen(Consumer<Beaten> listener) {
        told = listener;
    }

    public void note(Collection<String> asked, Collection<String> ids, boolean off, long millis) {
        if (ids.isEmpty()) {
            return;
        }
        String key = (off ? "off " : "on ") + String.join(",", new TreeSet<>(ids));
        Long before;
        synchronized (this) {
            before = this.best.get(key);
            if (before != null && millis >= before) {
                return;
            }
            this.best.put(key, millis);
            this.save();
        }
        if (before != null && millis <= before * MARGIN) {
            told.accept(new Beaten(List.copyOf(asked), List.copyOf(ids), off, millis, before));
        }
    }

    public synchronized @Nullable Best find(String id, boolean off) {
        Best found = null;
        for (Map.Entry<String, Long> entry : this.best.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(off ? "off " : "on ")) {
                List<String> ids = List.of(key.substring(key.indexOf(' ') + 1).split(","));
                if (ids.contains(id) && (found == null || ids.size() < found.together()
                        || (ids.size() == found.together() && entry.getValue() < found.millis()))) {
                    found = new Best(entry.getValue(), ids.size());
                }
            }
        }
        return found;
    }

    public static String format(long millis) {
        return String.format(Locale.ROOT, millis < 100 ? "%.2f" : "%.1f", Math.max(millis, 1) / 1000.0);
    }

    private void save() {
        if (this.path == null) {
            return;
        }
        StringBuilder lines = new StringBuilder();
        this.best.forEach((key, millis) -> lines.append(key).append(' ').append(millis).append('\n'));
        try {
            Files.createDirectories(this.path.getParent());
            AtomicFiles.writeString(this.path, lines.toString());
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the best times could not be saved", e);
        }
    }
}
