package dev.moon.hotwire.engine;

import dev.moon.hotwire.config.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.LoggerFactory;

public final class WorldContentRecord {
    public static final String FILE = "hotwire-content.txt";

    public record Entry(String id, String name, String version, LocalDate lastSeen) {
    }

    private WorldContentRecord() {
    }

    public static void note(Path world, List<Entry> mods, History history, Function<String, String> howGone)
            throws IOException {
        Map<String, Entry> before = new LinkedHashMap<>();
        read(world).forEach(one -> before.put(one.id(), one));
        Map<String, Entry> now = new LinkedHashMap<>();
        mods.forEach(one -> now.put(one.id(), one));
        List<String> lines = new ArrayList<>();
        now.values().forEach(one -> lines.add(clean(one.id()) + "\t" + clean(one.name()) + "\t" + clean(one.version())
                + "\t" + one.lastSeen()));
        AtomicFiles.writeString(world.resolve(FILE), String.join("\n", lines) + (lines.isEmpty() ? "" : "\n"));
        for (Entry one : now.values()) {
            Entry was = before.get(one.id());
            if (was == null) {
                history.add("+", one, "");
            } else if (!was.version().equals(one.version())) {
                history.add("~", one, "was " + was.version());
            }
        }
        for (Entry one : before.values()) {
            if (!now.containsKey(one.id())) {
                history.add("-", one, howGone.apply(one.id()) + ", last in it " + one.lastSeen());
            }
        }
    }

    public static List<Entry> findMissing(Path world, Predicate<String> isThere) {
        List<Entry> missing = new ArrayList<>(read(world).stream().filter(one -> !isThere.test(one.id())).toList());
        missing.sort((a, b) -> b.lastSeen().compareTo(a.lastSeen()));
        return missing;
    }

    static List<Entry> read(Path world) {
        Path file = world.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<Entry> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t", -1);
                if (parts.length == 4 && !parts[0].isBlank()) {
                    try {
                        out.add(new Entry(parts[0], parts[1], parts[2], LocalDate.parse(parts[3])));
                    } catch (DateTimeParseException e) {
                    }
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return out;
    }

    static String clean(String text) {
        return text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    public static final class History {
        public static final String FILE = "world-content-log.txt";
        private static final long MOST_BYTES = 8L * 1024 * 1024;
        private final Path file;
        private final String world;

        public History(Path file, String world) {
            this.file = file;
            this.world = clean(world);
        }

        public void add(String event, Entry mod, String detail) {
            this.write(event + "\t" + clean(mod.id()) + "\t" + clean(mod.name()) + "\t" + clean(mod.version()) + "\t"
                    + clean(detail));
        }

        public void addRefused(String cause) {
            this.write("!\t\t\t\t" + clean(cause));
        }

        private void write(String event) {
            String line = LocalDateTime.now(ZoneId.systemDefault()).withNano(0) + "\t" + this.world + "\t" + event
                    + "\n";
            try {
                Files.createDirectories(this.file.getParent());
                Files.writeString(this.file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
                if (Files.size(this.file) > MOST_BYTES) {
                    List<String> lines = Files.readAllLines(this.file, StandardCharsets.UTF_8);
                    AtomicFiles.writeString(this.file,
                            String.join("\n", lines.subList(lines.size() / 2, lines.size())) + "\n");
                }
            } catch (IOException e) {
                LoggerFactory.getLogger(WorldContentRecord.class).warn("[Hotwire] {} could not be written",
                        this.file, e);
            }
        }
    }
}
