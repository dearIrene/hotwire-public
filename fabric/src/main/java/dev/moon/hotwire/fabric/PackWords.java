package dev.moon.hotwire.fabric;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class PackWords {
    private static final Logger LOGGER = LoggerFactory.getLogger(PackWords.class);
    private static final String ENGLISH = "en_us";
    private static final String OWN = "hotwire.";
    private static volatile @Nullable Language readFor;
    private static volatile Map<String, String> words = Map.of();

    private PackWords() {
    }

    @SuppressWarnings("ReferenceEquality")
    static @Nullable String find(String key) {
        Language now = Language.getInstance();
        if (now != readFor) {
            synchronized (PackWords.class) {
                if (now != readFor) {
                    words = read();
                    readFor = now;
                }
            }
        }
        return words.get(key);
    }

    private static Map<String, String> read() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getResourceManager() == null || client.getLanguageManager() == null) {
            return Map.of();
        }
        List<PackResources> packs = client.getResourceManager().listPacks()
                .filter(pack -> !PackSource.SERVER.equals(pack.location().source())
                        && !PackSource.WORLD.equals(pack.location().source())).toList();
        Map<String, String> read = new HashMap<>();
        String chosen = client.getLanguageManager().getSelected();
        for (String code : chosen.equals(ENGLISH) ? List.of(ENGLISH) : List.of(ENGLISH, chosen)) {
            Identifier file = Identifier.tryBuild(HotwireClient.ID, "lang/" + code.toLowerCase(Locale.ROOT) + ".json");
            if (file == null) {
                continue;
            }
            for (PackResources pack : packs) {
                IoSupplier<InputStream> found = pack.getResource(PackType.CLIENT_RESOURCES, file);
                if (found != null) {
                    readInto(read, found, pack.packId());
                }
            }
        }
        return Map.copyOf(read);
    }

    private static void readInto(Map<String, String> into, IoSupplier<InputStream> file, String pack) {
        try (Reader reader = new InputStreamReader(file.get(), StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!(root instanceof JsonObject lines)) {
                return;
            }
            for (Map.Entry<String, JsonElement> line : lines.entrySet()) {
                JsonElement said = line.getValue();
                if (line.getKey().startsWith(OWN) && said.isJsonPrimitive() && said.getAsJsonPrimitive().isString()) {
                    into.put(line.getKey(), said.getAsString());
                }
            }
        } catch (IOException | JsonParseException e) {
            LOGGER.warn("[Hotwire] the pack {} has words for Hotwire that could not be read: {}", pack, e.toString());
        }
    }
}
