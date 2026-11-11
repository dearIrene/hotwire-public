package dev.moon.hotwire.fabric;

import dev.moon.hotwire.config.JsonFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class RestartOnlyFile {
    private static final Logger LOGGER = LoggerFactory.getLogger(RestartOnlyFile.class);

    @SuppressWarnings("FieldCanBeFinal")
    private static final class RestartJson {
        @Nullable String hotwire;
        @Nullable Map<String, String> mods = new TreeMap<>();
    }

    private final JsonFile<RestartJson> file;
    private final Function<String, @Nullable String> versionOf;
    private final Map<String, String> mods = new TreeMap<>();

    RestartOnlyFile(Path path, Function<String, @Nullable String> versionOf) {
        this.file = new JsonFile<>(path, RestartJson.class, RestartJson::new);
        this.versionOf = versionOf;
        RestartJson read = this.file.load();
        String own = versionOf.apply("hotwire");
        if (read.mods != null && own != null && own.equals(read.hotwire)) {
            read.mods.forEach((key, why) -> {
                if (why != null) {
                    this.mods.put(key, why);
                }
            });
        }
    }

    synchronized Map<String, String> findCurrent() {
        Map<String, String> out = new TreeMap<>();
        this.mods.forEach((key, why) -> {
            int at = key.lastIndexOf('@');
            if (at > 0 && key.substring(at + 1).equals(this.versionOf.apply(key.substring(0, at)))) {
                out.put(key.substring(0, at), why);
            }
        });
        return out;
    }

    synchronized void add(String id, String why) {
        String version = this.versionOf.apply(id);
        if (version == null || why.equals(this.mods.put(id + "@" + version, why))) {
            return;
        }
        RestartJson json = new RestartJson();
        json.hotwire = this.versionOf.apply("hotwire");
        json.mods = new TreeMap<>(this.mods);
        try {
            this.file.save(json);
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] that {} takes a restart to turn off stays for this session: {}", id, e.toString());
        }
    }
}
