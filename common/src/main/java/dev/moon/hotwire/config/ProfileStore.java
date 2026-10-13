package dev.moon.hotwire.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProfileStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProfileStore.class);
    private static final List<String> HEAD = List.of("# Hotwire profiles. One per line:  name = modid, modid, ...",
            "# Everything installed and NOT listed is switched off when",
                    "# the profile is applied. Safe to edit by hand.");

    public record ProfilePlan(List<String> turnOff, List<String> turnOn) {
        public ProfilePlan {
            turnOff = List.copyOf(turnOff);
            turnOn = List.copyOf(turnOn);
        }

        public boolean isEmpty() {
            return this.turnOff.isEmpty() && this.turnOn.isEmpty();
        }

        public String describe() {
            if (this.isEmpty()) {
                return "already exactly this";
            }
            List<String> parts = new ArrayList<>();
            if (!this.turnOff.isEmpty()) {
                parts.add(this.turnOff.size() + " off");
            }
            if (!this.turnOn.isEmpty()) {
                parts.add(this.turnOn.size() + " on");
            }
            return String.join(", ", parts);
        }
    }

    private final Path file;
    private final Map<String, Set<String>> profiles = new LinkedHashMap<>();
    private boolean loaded;

    public ProfileStore(Path file) {
        this.file = file;
    }

    public synchronized List<String> getNames() {
        this.load();
        return List.copyOf(this.profiles.keySet());
    }

    synchronized Set<String> getModsIn(String name) {
        this.load();
        return Collections.unmodifiableSet(new LinkedHashSet<>(this.profiles.getOrDefault(name, Set.of())));
    }

    synchronized boolean exists(String name) {
        this.load();
        return this.profiles.containsKey(name);
    }

    public synchronized void save(String name, Set<String> modsOn) throws IOException {
        this.load();
        this.profiles.put(name.strip(), new LinkedHashSet<>(modsOn));
        this.write();
    }

    public synchronized boolean rename(String from, String to) throws IOException {
        this.load();
        String wanted = to.strip();
        if (wanted.isEmpty() || !this.profiles.containsKey(from)
                || (this.profiles.containsKey(wanted) && !wanted.equals(from))) {
            return false;
        }
        Map<String, Set<String>> rebuilt = new LinkedHashMap<>();
        this.profiles.forEach((name, mods) -> rebuilt.put(name.equals(from) ? wanted : name, mods));
        this.profiles.clear();
        this.profiles.putAll(rebuilt);
        this.write();
        return true;
    }

    public synchronized void delete(String name) throws IOException {
        this.load();
        this.profiles.remove(name);
        this.write();
    }

    public ProfilePlan planFor(String name, List<String> installed, Predicate<String> on) {
        Set<String> wanted = this.getModsIn(name);
        List<String> off = new ArrayList<>();
        List<String> onNow = new ArrayList<>();
        for (String id : installed) {
            boolean should = wanted.contains(id);
            boolean is = on.test(id);
            if (is && !should) {
                off.add(id);
            } else if (!is && should) {
                onNow.add(id);
            }
        }
        return new ProfilePlan(off, onNow);
    }

    private void load() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        if (Files.notExists(this.file)) {
            return;
        }
        try {
            Files.readAllLines(this.file, StandardCharsets.UTF_8).forEach(this::parse);
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] {} could not be read", this.file, e);
        }
    }

    private void parse(String line) {
        String trimmed = line.strip();
        int split = trimmed.indexOf('=');
        if (trimmed.startsWith("#") || split <= 0) {
            return;
        }
        String name = trimmed.substring(0, split).strip();
        Set<String> ids = new LinkedHashSet<>();
        for (String id : trimmed.substring(split + 1).split(",", -1)) {
            if (!id.isBlank()) {
                ids.add(id.strip());
            }
        }
        if (!name.isEmpty()) {
            this.profiles.put(name, ids);
        }
    }

    private void write() throws IOException {
        List<String> lines = new ArrayList<>(HEAD);
        this.profiles.forEach((name, mods) -> lines.add(name + " = " + String.join(", ", mods)));
        AtomicFiles.writeString(this.file, String.join(System.lineSeparator(), lines) + System.lineSeparator());
    }
}
