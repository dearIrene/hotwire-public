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

    private static final Map<String, String> SAME_LANGUAGE = Map.ofEntries(Map.entry("es", "es_es"),
            Map.entry("pt", "pt_br"), Map.entry("de", "de_de"), Map.entry("fr", "fr_fr"), Map.entry("ru", "ru_ru"),
            Map.entry("it", "it_it"), Map.entry("nl", "nl_nl"), Map.entry("pl", "pl_pl"), Map.entry("cs", "cs_cz"),
            Map.entry("da", "da_dk"), Map.entry("fi", "fi_fi"), Map.entry("sv", "sv_se"), Map.entry("no", "no_no"),
            Map.entry("nb", "no_no"), Map.entry("nn", "no_no"), Map.entry("el", "el_gr"), Map.entry("hu", "hu_hu"),
            Map.entry("id", "id_id"), Map.entry("ja", "ja_jp"), Map.entry("ro", "ro_ro"), Map.entry("th", "th_th"),
            Map.entry("tr", "tr_tr"), Map.entry("uk", "uk_ua"), Map.entry("vi", "vi_vn"));
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
        String lower = chosen.toLowerCase(Locale.ROOT);
        int region = lower.indexOf('_');
        String same = SAME_LANGUAGE.get(region < 0 ? lower : lower.substring(0, region));
        List<String> codes = chosen.equals(ENGLISH) ? List.of(ENGLISH)
                : same == null || same.equals(lower) ? List.of(ENGLISH, chosen) : List.of(ENGLISH, same, chosen);
        for (String code : codes) {
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
