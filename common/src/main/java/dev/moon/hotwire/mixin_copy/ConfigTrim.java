package dev.moon.hotwire.mixin_copy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

public final class ConfigTrim {
    public static final String LIVE_PLUGIN = "dev.moon.hotwire.mixin_copy.copy.LivePluginShape";

    private ConfigTrim() {
    }

    public static @Nullable String readRefmapOf(byte[] json) {
        JsonObject root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        return isName(root.get("refmap")) ? root.get("refmap").getAsString() : null;
    }

    public static @Nullable String readPluginOf(byte[] json) {
        JsonObject root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        return isName(root.get("plugin")) ? root.get("plugin").getAsString() : null;
    }

    public static Set<String> readListed(byte[] json, String side) {
        JsonObject root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        Set<String> full = new TreeSet<>();
        readListedIn(root, side).forEach(entry -> full.add(getPrefixOf(root) + entry));
        return full;
    }

    public static String readPackageOf(byte[] json) {
        return getPrefixOf(JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject());
    }

    private static String getPrefixOf(JsonObject root) {
        return isName(root.get("package")) ? root.get("package").getAsString() + "." : "";
    }

    private static Set<String> readListedIn(JsonObject root, String side) {
        Set<String> listed = new TreeSet<>();
        for (String name : new String[] {"mixins", side.toLowerCase(Locale.ROOT)}) {
            if (root.get(name) instanceof JsonArray array) {
                for (JsonElement entry : array) {
                    if (isName(entry)) {
                        listed.add(entry.getAsString());
                    }
                }
            }
        }
        return listed;
    }

    public static byte[] repeatDecisions(byte[] json, String side, UnaryOperator<Set<String>> choose,
            Set<String> applied) {
        return repeatDecisions(json, side, choose, applied, name -> false, plugin -> false, plugin -> false);
    }

    public static byte[] repeatDecisions(byte[] json, String side, UnaryOperator<Set<String>> choose,
            Set<String> applied, Predicate<String> listedElsewhere, Predicate<String> retainsPlugin,
            Predicate<String> runsLive) {
        JsonObject root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        String prefix = getPrefixOf(root);
        Set<String> full = new TreeSet<>();
        readListedIn(root, side).forEach(entry -> full.add(prefix + entry));
        Set<String> retained = new TreeSet<>();
        for (String mixin : choose.apply(full)) {
            if (mixin.startsWith(prefix)) {
                retained.add(mixin.substring(prefix.length()));
            }
        }
        boolean pluginStays = isName(root.get("plugin")) && retainsPlugin.test(root.get("plugin").getAsString());

        boolean live = pluginStays && runsLive.test(root.get("plugin").getAsString());

        if (!prefix.isEmpty() && (!pluginStays || live)) {
            applied.stream().filter(mixin -> mixin.startsWith(prefix) && !listedElsewhere.test(mixin))
                    .map(mixin -> mixin.substring(prefix.length())).forEach(retained::add);
        }
        JsonArray list = new JsonArray();
        retained.forEach(list::add);
        root.remove("client");
        root.remove("server");
        root.add("mixins", list);
        if (live) {
            root.addProperty("plugin", LIVE_PLUGIN);
        } else if (!pluginStays) {
            root.remove("plugin");
        }

        JsonObject injectors = root.has("injectors") && root.get("injectors").isJsonObject()
                ? root.getAsJsonObject("injectors") : new JsonObject();
        injectors.addProperty("defaultRequire", 0);
        root.add("injectors", injectors);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isName(@Nullable JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }
}
