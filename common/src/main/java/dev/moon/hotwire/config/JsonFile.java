package dev.moon.hotwire.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JsonFile<T> {
    private static final Logger LOGGER = LoggerFactory.getLogger(JsonFile.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path path;
    private final Class<T> type;
    private final Supplier<T> defaults;

    private volatile boolean readOnly;

    public JsonFile(Path path, Class<T> type, Supplier<T> defaults) {
        this.path = path;
        this.type = type;
        this.defaults = defaults;
    }

    public T load() {
        if (Files.notExists(this.path)) {
            return this.defaults.get();
        }
        try (Reader reader = Files.newBufferedReader(this.path, StandardCharsets.UTF_8)) {
            T value = GSON.fromJson(reader, this.type);
            if (value != null) {
                return value;
            }
        } catch (IOException | JsonParseException e) {
            LOGGER.warn("[Hotwire] {} could not be read", this.path, e);
        }
        this.setAside();
        return this.defaults.get();
    }

    public void save(T value) throws IOException {
        if (this.readOnly) {
            throw new IOException(this.path + " could not be read or kept aside, so it is left as it is");
        }
        AtomicFiles.writeString(this.path, GSON.toJson(value, this.type));
    }

    private void setAside() {
        Path aside = this.getSibling(".broken");
        for (int copy = 2; Files.exists(aside); copy++) {
            aside = this.getSibling(".broken-" + copy);
        }
        try {
            Files.move(this.path, aside);
            this.readOnly = false;
            LOGGER.warn("[Hotwire] {} was kept as {}, and defaults are used", this.path, aside);
        } catch (IOException e) {
            this.readOnly = true;
            LOGGER.error("[Hotwire] {} could not be kept aside; it is not saved over", this.path, e);
        }
    }

    private Path getSibling(String suffix) {
        return this.path.resolveSibling(this.path.getFileName() + suffix);
    }
}
