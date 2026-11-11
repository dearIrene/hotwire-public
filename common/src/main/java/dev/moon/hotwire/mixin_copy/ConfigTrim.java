package dev.moon.hotwire.mixin_copy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

public final class ConfigTrim {
    private static final String UNLISTED = "hotwire:unlisted";

    public static final String LIVE_PLUGIN = "dev.moon.hotwire.mixin_copy.copy.LivePluginShape";

    public static final String RETAINED_PLUGIN = "dev.moon.hotwire.mixin_copy.copy.RetainedPluginShape";

    private ConfigTrim() {
    }

    public static byte[] addUnlisted(byte[] json, Set<String> mixins) {
        JsonObject root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray names = new JsonArray();
        new TreeSet<>(mixins).forEach(names::add);
        root.add(UNLISTED, names);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static Set<String> readUnlisted(byte[] json) {
        JsonObject root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        Set<String> names = new TreeSet<>();
        if (root.has(UNLISTED)) {
            root.getAsJsonArray(UNLISTED).forEach(name -> names.add(name.getAsString()));
        }
        return names;
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
            for (String mixin : findInPackage(applied, prefix)) {
                if (!listedElsewhere.test(mixin)) {
                    retained.add(mixin.substring(prefix.length()));
                }
            }
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
        } else {
            root.addProperty("hotwirePlugin", root.get("plugin").getAsString());
            root.addProperty("plugin", RETAINED_PLUGIN);
            JsonArray decided = new JsonArray();
            if (!prefix.isEmpty()) {
                for (String mixin : findInPackage(applied, prefix)) {
                    if (!listedElsewhere.test(mixin)) {
                        decided.add(mixin);
                    }
                }
            }
            root.add("hotwireApplied", decided);
        }

        JsonObject injectors = root.has("injectors") && root.get("injectors").isJsonObject()
                ? root.getAsJsonObject("injectors") : new JsonObject();
        injectors.addProperty("defaultRequire", 0);
        root.add("injectors", injectors);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] listOnly(byte[] json, String mixin) {
        JsonObject root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        String prefix = getPrefixOf(root);
        JsonArray list = new JsonArray();
        list.add(mixin.startsWith(prefix) ? mixin.substring(prefix.length()) : mixin);
        root.remove("client");
        root.remove("server");
        root.add("mixins", list);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static List<String> findInPackage(Set<String> names, String prefix) {
        List<String> found = new ArrayList<>();
        if (names instanceof NavigableSet<String> sorted && sorted.comparator() == null) {
            for (String name : sorted.tailSet(prefix, true)) {
                if (!name.startsWith(prefix)) {
                    break;
                }
                found.add(name);
            }
            return found;
        }
        for (String name : names) {
            if (name.startsWith(prefix)) {
                found.add(name);
            }
        }
        return found;
    }

    private static boolean isName(@Nullable JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }
}
