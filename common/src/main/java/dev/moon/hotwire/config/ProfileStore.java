package dev.moon.hotwire.config;

import dev.moon.hotwire.ui.Lang;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProfileStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProfileStore.class);
    private static final List<String> HEAD = List.of("# Hotwire profiles. One per line:  name = modid, modid, ...",
            "# Everything installed and NOT listed is switched off when",
                    "# the profile is applied. Safe to edit by hand.",
            "# A profile's servers:  @name = address, address  (singleplayer for your own worlds,",
            "# * for any part of an address: *.example.net)");

    public static final String SINGLEPLAYER = "singleplayer";
    private static final String DEFAULT_PORT = ":25565";

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
                return Lang.get("hotwire.setup.plan_same");
            }
            List<String> parts = new ArrayList<>();
            if (!this.turnOff.isEmpty()) {
                parts.add(Lang.get("hotwire.setup.plan_off", this.turnOff.size()));
            }
            if (!this.turnOn.isEmpty()) {
                parts.add(Lang.get("hotwire.setup.plan_on", this.turnOn.size()));
            }
            return String.join(", ", parts);
        }
    }

    private final Path file;
    private final Map<String, Set<String>> profiles = new LinkedHashMap<>();

    private final Map<String, Set<String>> servers = new LinkedHashMap<>();

    private @Nullable String current;
    private static final String CURRENT = "#current";
    private boolean loaded;

    private boolean unread;

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

    public synchronized void save(String name, Set<String> modsOn) throws IOException {
        this.load();
        String cleaned = clean(name);
        if (cleaned.isEmpty()) {
            return;
        }
        this.profiles.put(cleaned, new LinkedHashSet<>(modsOn));
        this.current = cleaned;
        this.write();
    }

    public synchronized @Nullable String getCurrent() {
        this.load();
        return this.current != null && this.profiles.containsKey(this.current) ? this.current : null;
    }

    public synchronized void setCurrent(String name) throws IOException {
        this.load();
        if (this.profiles.containsKey(name) && !name.equals(this.current)) {
            this.current = name;
            this.write();
        }
    }

    public synchronized void follow(Collection<String> switched, boolean off) throws IOException {
        String name = this.getCurrent();
        Set<String> mods = name == null ? null : this.profiles.get(name);
        if (mods != null && (off ? mods.removeAll(switched) : mods.addAll(switched))) {
            this.write();
        }
    }

    public synchronized boolean rename(String from, String to) throws IOException {
        this.load();
        String wanted = clean(to);
        if (wanted.isEmpty() || !this.profiles.containsKey(from)
                || (this.profiles.containsKey(wanted) && !wanted.equals(from))) {
            return false;
        }
        Map<String, Set<String>> rebuilt = new LinkedHashMap<>();
        this.profiles.forEach((name, mods) -> rebuilt.put(name.equals(from) ? wanted : name, mods));
        this.profiles.clear();
        this.profiles.putAll(rebuilt);
        if (from.equals(this.current)) {
            this.current = wanted;
        }
        Set<String> moved = this.servers.remove(from);
        if (moved != null) {
            this.servers.put(wanted, moved);
        }
        this.write();
        return true;
    }

    public synchronized void delete(String name) throws IOException {
        this.load();
        this.profiles.remove(name);
        this.servers.remove(name);
        if (name.equals(this.current)) {
            this.current = null;
        }
        this.write();
    }

    public synchronized List<String> getServersOf(String name) {
        this.load();
        return List.copyOf(this.servers.getOrDefault(name, Set.of()));
    }

    public synchronized boolean addServer(String name, String address) throws IOException {
        this.load();
        String server = normalize(address);
        if (server.isEmpty() || !this.profiles.containsKey(name)) {
            return false;
        }
        this.servers.values().forEach(of -> of.remove(server));
        this.servers.computeIfAbsent(name, any -> new LinkedHashSet<>()).add(server);
        this.write();
        return true;
    }

    public synchronized void removeServer(String name, String address) throws IOException {
        this.load();
        Set<String> of = this.servers.get(name);
        if (of != null && of.remove(normalize(address))) {
            this.write();
        }
    }

    public synchronized @Nullable String findFor(String address) {
        this.load();
        String server = normalize(address);
        for (Map.Entry<String, Set<String>> entry : this.servers.entrySet()) {
            if (entry.getValue().contains(server) && this.profiles.containsKey(entry.getKey())) {
                return entry.getKey();
            }
        }

        for (Map.Entry<String, Set<String>> entry : this.servers.entrySet()) {
            if (!this.profiles.containsKey(entry.getKey())) {
                continue;
            }
            for (String pattern : entry.getValue()) {
                if (pattern.indexOf('*') >= 0 && isMatching(pattern, server)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private static boolean isMatching(String pattern, String server) {
        return server.matches("\\Q" + pattern.replace("*", "\\E.*\\Q") + "\\E");
    }

    public static String normalize(String address) {
        String server = address.strip().toLowerCase(Locale.ROOT);
        if (server.endsWith(DEFAULT_PORT)) {
            server = server.substring(0, server.length() - DEFAULT_PORT.length());
        }
        return server.matches("[a-z0-9._:*\\[\\]-]{1,255}") ? server : "";
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
            this.unread = true;
        }
    }

    private static String clean(String name) {
        return name.replace('=', '-').replaceFirst("^[#@\\s]+", "").strip();
    }

    private void parse(String line) {
        String trimmed = line.strip();
        int split = trimmed.indexOf('=');
        if (split > 0 && trimmed.substring(0, split).strip().equals(CURRENT)) {
            this.current = trimmed.substring(split + 1).strip();
            return;
        }
        if (trimmed.startsWith("#") || split <= 0) {
            return;
        }
        if (trimmed.startsWith("@")) {
            Set<String> of = new LinkedHashSet<>();
            for (String address : trimmed.substring(split + 1).split(",", -1)) {
                String server = normalize(address);
                if (!server.isEmpty()) {
                    of.add(server);
                }
            }
            String owner = trimmed.substring(1, split).strip();
            if (!owner.isEmpty() && !of.isEmpty()) {
                this.servers.put(owner, of);
            }
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
        if (this.unread) {
            Files.copy(this.file, this.file.resolveSibling(this.file.getFileName() + ".unread"),
                    StandardCopyOption.REPLACE_EXISTING);
            this.unread = false;
        }
        List<String> lines = new ArrayList<>(HEAD);
        if (this.current != null && this.profiles.containsKey(this.current)) {
            lines.add(CURRENT + " = " + this.current);
        }
        this.profiles.forEach((name, mods) -> lines.add(name + " = " + String.join(", ", mods)));
        this.servers.forEach((name, of) -> {
            if (!of.isEmpty() && this.profiles.containsKey(name)) {
                lines.add("@" + name + " = " + String.join(", ", of));
            }
        });
        AtomicFiles.writeString(this.file, String.join(System.lineSeparator(), lines) + System.lineSeparator());
    }
}
