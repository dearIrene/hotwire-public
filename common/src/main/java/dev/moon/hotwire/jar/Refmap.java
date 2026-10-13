package dev.moon.hotwire.jar;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

record Refmap(Map<String, Map<String, String>> mappings) {
    static final Refmap NONE = new Refmap(Map.of());

    static Refmap of(byte[] config, ModJarReader.EntryReader reader) throws IOException {
        JsonObject root = parseObject(config);
        JsonElement name = root == null ? null : root.get("refmap");
        byte[] bytes = name != null && name.isJsonPrimitive() ? reader.read(name.getAsString()) : null;
        JsonObject refmap = bytes == null ? null : parseObject(bytes);
        JsonElement mappings = refmap == null ? null : refmap.get("mappings");
        if (mappings == null || !mappings.isJsonObject()) {
            return NONE;
        }
        Map<String, Map<String, String>> out = new HashMap<>();
        mappings.getAsJsonObject().entrySet().forEach(mixin -> {
            if (mixin.getValue().isJsonObject()) {
                Map<String, String> of = new HashMap<>();
                mixin.getValue().getAsJsonObject().entrySet().forEach(entry -> {
                    if (entry.getValue().isJsonPrimitive()) {
                        of.put(entry.getKey(), entry.getValue().getAsString());
                    }
                });
                out.put(mixin.getKey(), of);
            }
        });
        return new Refmap(out);
    }

    String getTarget(String mixin, String written) {
        String mapped = this.mappings.getOrDefault(mixin, Map.of()).get(written);
        return (mapped == null ? written : mapped).replace('.', '/');
    }

    private static @Nullable JsonObject parseObject(byte[] json) {
        try {
            JsonElement parsed = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (JsonParseException e) {
            return null;
        }
    }
}
