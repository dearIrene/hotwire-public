package dev.moon.hotwire.fabric;

import dev.moon.hotwire.config.JsonFile;
import dev.moon.hotwire.plan.KnownUses;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SeenUses implements KnownUses {
    private static final Logger LOGGER = LoggerFactory.getLogger(SeenUses.class);
    private static final int FORMAT = 1;

    @SuppressWarnings("FieldCanBeFinal")
    private static final class UsesJson {
        int format = FORMAT;
        @Nullable Map<String, List<String>> uses = new TreeMap<>();
    }

    private final JsonFile<UsesJson> file;
    private final Function<String, @Nullable String> versionOf;
    private final Map<String, Set<String>> uses = new TreeMap<>();

    SeenUses(Path path, Function<String, @Nullable String> versionOf) {
        this.file = new JsonFile<>(path, UsesJson.class, UsesJson::new);
        this.versionOf = versionOf;
        UsesJson read = this.file.load();
        if (read.format > FORMAT) {
            LOGGER.warn("[Hotwire] uses.json is from a newer Hotwire (format {}); what this one knows is read",
                    read.format);
        }
        Map<String, List<String>> saved = read.uses;
        if (saved != null) {
            saved.forEach((user, used) -> this.uses.put(user, new TreeSet<>(used)));
        }
    }

    synchronized boolean add(String user, String used) {
        String version = this.versionOf.apply(user);
        if (version == null || !this.uses.computeIfAbsent(user + "@" + version, key -> new TreeSet<>()).add(used)) {
            return false;
        }
        UsesJson json = new UsesJson();
        Map<String, List<String>> out = new TreeMap<>();
        this.uses.forEach((key, all) -> out.put(key, new ArrayList<>(all)));
        json.uses = out;
        try {
            this.file.save(json);
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] what {} was seen using stays for this session: {}", user, e.toString());
        }
        return true;
    }

    @Override
    public synchronized Set<String> findUsersOf(String used) {
        Set<String> out = new HashSet<>();
        this.uses.forEach((key, all) -> {
            int at = key.lastIndexOf('@');
            String user = key.substring(0, at);
            if (all.contains(used) && key.substring(at + 1).equals(this.versionOf.apply(user))) {
                out.add(user);
            }
        });
        return out;
    }

    @Override
    public synchronized Set<String> findUsedBy(String user) {
        String version = this.versionOf.apply(user);
        return version == null ? Set.of() : Set.copyOf(this.uses.getOrDefault(user + "@" + version, Set.of()));
    }
}
