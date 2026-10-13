package dev.moon.hotwire.jar;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.moon.hotwire.jar.ModJarException.Reason;
import dev.moon.hotwire.jar.ModManifest.Entrypoint;
import dev.moon.hotwire.jar.ModManifest.Environment;
import dev.moon.hotwire.jar.ModManifest.MixinConfigRef;
import dev.moon.hotwire.jar.ModManifest.Relation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public final class ModManifestParser {
    private ModManifestParser() {
    }

    public static ModManifest parse(byte[] json, String jar) throws ModJarException {
        JsonObject root = parseRoot(json, jar);
        int schema = asNumber(root.get("schemaVersion")).orElse(0);
        if (schema != 1) {
            throw new ModJarException(Reason.UNSUPPORTED_SCHEMA, jar,
                    "its fabric.mod.json uses schema version " + schema + ", and Hotwire reads version 1");
        }
        String id = asText(root.get("id"))
                .orElseThrow(() -> new ModJarException(Reason.NO_ID, jar, "its fabric.mod.json declares no id"));
        return new ModManifest(
                id,
                asText(root.get("version")).orElse(""),
                asText(root.get("name")).orElse(id),
                asText(root.get("description")).orElse(""),
                parseAuthors(root),
                parseIcon(root.get("icon")),
                parseEnvironment(root.get("environment")),
                parseEntrypoints(root),
                parseMixinConfigs(root),
                asText(root.get("accessWidener")).or(() -> asText(root.get("classTweaker"))),
                parseRelations(root),
                asTexts(root.get("provides")),
                readNestedJars(root),
                parseMenuParent(root, id),
                parseBadges(root).contains("library"));
    }

    private static JsonObject parseRoot(byte[] json, String jar) throws ModJarException {
        String text = new String(json, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (JsonParseException e) {
            throw new ModJarException(Reason.MANIFEST_NOT_JSON, jar, "its fabric.mod.json is not valid JSON", e);
        }
        throw new ModJarException(Reason.MANIFEST_NOT_JSON, jar, "its fabric.mod.json is not a JSON object");
    }

    private static List<String> parseAuthors(JsonObject root) {
        List<String> out = new ArrayList<>();
        for (JsonElement author : asElements(root.get("authors"))) {
            readNamed(author, "name").ifPresent(out::add);
        }
        return out;
    }

    private static Optional<String> parseIcon(@Nullable JsonElement icon) {
        if (icon == null || !icon.isJsonObject()) {
            return asText(icon);
        }
        String best = null;
        int bestSize = -1;
        for (Map.Entry<String, JsonElement> size : icon.getAsJsonObject().entrySet()) {
            Optional<String> path = asText(size.getValue());
            int pixels = parseSize(size.getKey());
            if (path.isPresent() && pixels > bestSize) {
                best = path.get();
                bestSize = pixels;
            }
        }
        return Optional.ofNullable(best);
    }

    private static int parseSize(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Environment parseEnvironment(@Nullable JsonElement environment) {
        return switch (asText(environment).orElse("*")) {
            case "client" -> Environment.CLIENT;
            case "server" -> Environment.SERVER;
            default -> Environment.BOTH;
        };
    }

    private static Map<String, List<Entrypoint>> parseEntrypoints(JsonObject root) {
        Map<String, List<Entrypoint>> out = new LinkedHashMap<>();
        JsonElement declared = root.get("entrypoints");
        if (declared == null || !declared.isJsonObject()) {
            return out;
        }
        for (Map.Entry<String, JsonElement> key : declared.getAsJsonObject().entrySet()) {
            List<Entrypoint> entries = new ArrayList<>();
            for (JsonElement entry : asElements(key.getValue())) {
                if (entry.isJsonObject()) {
                    JsonObject object = entry.getAsJsonObject();
                    asText(object.get("value")).ifPresent(value -> entries.add(new Entrypoint(value,
                            asText(object.get("adapter")).orElse(Entrypoint.DEFAULT_ADAPTER))));
                } else {
                    asText(entry).ifPresent(value -> entries.add(new Entrypoint(value, Entrypoint.DEFAULT_ADAPTER)));
                }
            }
            out.put(key.getKey(), List.copyOf(entries));
        }
        return out;
    }

    private static List<MixinConfigRef> parseMixinConfigs(JsonObject root) {
        List<MixinConfigRef> out = new ArrayList<>();
        for (JsonElement entry : asElements(root.get("mixins"))) {
            if (entry.isJsonObject()) {
                JsonObject object = entry.getAsJsonObject();
                asText(object.get("config")).ifPresent(config ->
                        out.add(new MixinConfigRef(config, parseEnvironment(object.get("environment")))));
            } else {
                asText(entry).ifPresent(config -> out.add(new MixinConfigRef(config, Environment.BOTH)));
            }
        }
        return out;
    }

    private static Map<Relation, Map<String, List<String>>> parseRelations(JsonObject root) {
        Map<Relation, Map<String, List<String>>> out = new EnumMap<>(Relation.class);
        for (Relation relation : Relation.values()) {
            JsonElement declared = root.get(relation.getKey());
            if (declared == null || !declared.isJsonObject()) {
                continue;
            }
            Map<String, List<String>> ranges = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> mod : declared.getAsJsonObject().entrySet()) {
                if (!mod.getKey().isBlank()) {
                    ranges.put(mod.getKey(), mod.getValue().isJsonArray() ? asTexts(mod.getValue())
                            : asText(mod.getValue()).map(List::of).orElse(List.of("*")));
                }
            }
            out.put(relation, Map.copyOf(ranges));
        }
        return out;
    }

    private static List<String> readNestedJars(JsonObject root) {
        List<String> out = new ArrayList<>();
        for (JsonElement entry : asElements(root.get("jars"))) {
            readNamed(entry, "file").ifPresent(out::add);
        }
        return out;
    }

    private static Optional<String> parseMenuParent(JsonObject root, String id) {
        return parseModMenu(root).flatMap(menu -> readNamed(menu.get("parent"), "id"))
                .filter(parent -> !parent.equals(id));
    }

    private static List<String> parseBadges(JsonObject root) {
        return parseModMenu(root).map(menu -> asTexts(menu.get("badges"))).orElse(List.of());
    }

    private static Optional<JsonObject> parseModMenu(JsonObject root) {
        return asObject(root.get("custom")).flatMap(custom -> asObject(custom.get("modmenu")));
    }

    private static Optional<JsonObject> asObject(@Nullable JsonElement element) {
        return element != null && element.isJsonObject() ? Optional.of(element.getAsJsonObject()) : Optional.empty();
    }

    private static Optional<String> readNamed(@Nullable JsonElement element, String key) {
        return element != null && element.isJsonObject() ? asText(element.getAsJsonObject().get(key)) : asText(element);
    }

    private static List<JsonElement> asElements(@Nullable JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<JsonElement> out = new ArrayList<>();
        element.getAsJsonArray().forEach(out::add);
        return out;
    }

    private static List<String> asTexts(@Nullable JsonElement element) {
        List<String> out = new ArrayList<>();
        for (JsonElement entry : asElements(element)) {
            asText(entry).ifPresent(out::add);
        }
        return out;
    }

    private static Optional<String> asText(@Nullable JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return Optional.empty();
        }
        String value = element.getAsString();
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static Optional<Integer> asNumber(@Nullable JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? Optional.of(element.getAsInt()) : Optional.empty();
    }
}
