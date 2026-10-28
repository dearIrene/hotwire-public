package dev.moon.hotwire.modrinth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.moon.hotwire.config.AtomicFiles;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class UpdateMemory {
    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateMemory.class);

    static final Duration UNKNOWN_LASTS = Duration.ofDays(7);

    public record Entry(String projectId, String updated, @Nullable Newer newer, long checkedAt) {
        public boolean isUnknown() {
            return this.projectId.isEmpty();
        }
    }

    public record Newer(String versionId, String number, String fileName, String sha512, long size,
            List<String> requiredProjects, String note) {
        public static Newer of(ModrinthClient.Version version) {
            String note = version.changelog().lines().map(String::strip).filter(line -> !line.isEmpty())
                    .findFirst().orElse("");
            return new Newer(version.id(), version.number(), version.fileName(), version.sha512(), version.size(),
                    version.requiredProjects(), note.length() > 300 ? note.substring(0, 300) : note);
        }

        public ModrinthClient.Version toVersion(String projectId) {
            String url = "https://cdn.modrinth.com/data/" + projectId + "/versions/" + this.versionId + "/"
                    + URLEncoder.encode(this.fileName, StandardCharsets.UTF_8).replace("+", "%20");
            return new ModrinthClient.Version(this.versionId, projectId, this.number, this.number, this.fileName, url,
                    this.sha512, this.size, List.of(), this.requiredProjects, this.note);
        }
    }

    private final Path file;
    private final Map<String, Entry> byHash = new HashMap<>();
    private final Set<String> told = new HashSet<>();

    private UpdateMemory(Path file) {
        this.file = file;
    }

    public static UpdateMemory read(Path file) {
        UpdateMemory memory = new UpdateMemory(file);
        if (!Files.isRegularFile(file)) {
            return memory;
        }
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(Files.readAllBytes(file)))) {
            JsonObject root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (Map.Entry<String, JsonElement> one : root.getAsJsonObject("jars").entrySet()) {
                JsonObject entry = one.getValue().getAsJsonObject();
                JsonElement newer = entry.get("n");
                memory.byHash.put(one.getKey(), new Entry(entry.get("p").getAsString(), entry.get("u").getAsString(),
                        newer == null || !newer.isJsonObject() ? null : readNewer(newer.getAsJsonObject()),
                        entry.get("t").getAsLong()));
            }
            root.getAsJsonArray("told").forEach(id -> memory.told.add(id.getAsString()));
        } catch (IOException | RuntimeException e) {
            LOGGER.info("[Hotwire] the update memory in {} could not be read: {}", file, e.toString());
            memory.byHash.clear();
            memory.told.clear();
        }
        return memory;
    }

    private static Newer readNewer(JsonObject json) {
        List<String> required = new ArrayList<>();
        json.getAsJsonArray("r").forEach(id -> required.add(id.getAsString()));
        return new Newer(json.get("v").getAsString(), json.get("n").getAsString(), json.get("f").getAsString(),
                json.get("h").getAsString(), json.get("s").getAsLong(), List.copyOf(required),
                json.get("c").getAsString());
    }

    private static JsonObject writeNewer(Newer newer) {
        JsonObject json = new JsonObject();
        json.addProperty("v", newer.versionId());
        json.addProperty("n", newer.number());
        json.addProperty("f", newer.fileName());
        json.addProperty("h", newer.sha512());
        json.addProperty("s", newer.size());
        JsonArray required = new JsonArray();
        newer.requiredProjects().forEach(required::add);
        json.add("r", required);
        json.addProperty("c", newer.note());
        return json;
    }

    public void write() {
        JsonObject root = new JsonObject();
        JsonObject jars = new JsonObject();
        this.byHash.forEach((hash, entry) -> {
            JsonObject one = new JsonObject();
            one.addProperty("p", entry.projectId());
            one.addProperty("u", entry.updated());
            Newer newer = entry.newer();
            if (newer != null) {
                one.add("n", writeNewer(newer));
            }
            one.addProperty("t", entry.checkedAt());
            jars.add(hash, one);
        });
        root.add("jars", jars);
        JsonArray toldIds = new JsonArray();
        this.told.forEach(toldIds::add);
        root.add("told", toldIds);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        try {
            Path parent = this.file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            AtomicFiles.write(this.file, bytes.toByteArray());
        } catch (IOException e) {
            LOGGER.info("[Hotwire] the update memory could not be saved to {}: {}", this.file, e.toString());
        }
    }

    public void retain(Collection<String> hashes) {
        this.byHash.keySet().retainAll(Set.copyOf(hashes));
    }

    public @Nullable Entry find(String hash) {
        return this.byHash.get(hash);
    }

    public void put(String hash, Entry entry) {
        this.byHash.put(hash, entry);
    }

    public Set<String> findProjects() {
        Set<String> projects = new HashSet<>();
        this.byHash.values().stream().filter(entry -> !entry.isUnknown()).forEach(entry -> projects.add(entry
                .projectId()));
        return projects;
    }

    public Map<String, String> findToAsk(Map<String, String> hashByName, Map<String, String> stamps, Instant now) {
        Map<String, String> ask = new LinkedHashMap<>();
        hashByName.forEach((name, hash) -> {
            Entry entry = this.byHash.get(hash);
            boolean stale = entry == null
                    || (entry.isUnknown() ? Instant.ofEpochMilli(entry.checkedAt()).isBefore(now.minus(UNKNOWN_LASTS))
                            : !entry.updated().equals(stamps.getOrDefault(entry.projectId(), "")));
            if (stale) {
                ask.put(name, hash);
            }
        });
        return ask;
    }

    public boolean tell(String versionId) {
        return this.told.add(versionId);
    }

    public void retainTold(Collection<String> versionIds) {
        this.told.retainAll(Set.copyOf(versionIds));
    }
}
