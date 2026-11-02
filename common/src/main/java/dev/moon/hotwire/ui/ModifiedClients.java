package dev.moon.hotwire.ui;

import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

public final class ModifiedClients {
    private static final Map<String, String> BY_MOD = Map.of(
            "ichor", "Lunar Client",
            "feather", "Feather Client",
            "feather-core", "Feather Client",
            "labymod", "LabyMod");
    private static final Map<String, String> BY_FOLDER = Map.of(
            ".lunarclient", "Lunar Client",
            ".feather", "Feather Client",
            "labymod-neo", "LabyMod");

    public static final String DAWN = "Dawn Client";

    private static final String DAWN_MOD = "dawn";
    private static final String DAWN_FOLDER = "/.dawn/";
    private static final String DAWN_FILE = "mixins.feather.json";

    private static volatile @Nullable String current;
    private static volatile boolean promptDue;

    private ModifiedClients() {
    }

    public static @Nullable String getCurrent() {
        return current;
    }

    public static void setCurrent(String client) {
        current = client;
        promptDue = true;
    }

    static boolean takePromptDue() {
        boolean due = promptDue;
        promptDue = false;
        return due;
    }

    public static boolean isDawn(Predicate<String> isLoaded, String paths, Predicate<String> hasFile) {
        String lower = paths.toLowerCase(Locale.ROOT).replace('\\', '/');
        return isLoaded.test(DAWN_MOD) && (lower.contains(DAWN_FOLDER) || hasFile.test(DAWN_FILE));
    }

    public static @Nullable String find(Predicate<String> isLoaded, String paths) {
        for (Map.Entry<String, String> mod : BY_MOD.entrySet()) {
            if (isLoaded.test(mod.getKey())) {
                return mod.getValue();
            }
        }
        String lower = paths.toLowerCase(Locale.ROOT).replace('\\', '/');
        for (Map.Entry<String, String> folder : BY_FOLDER.entrySet()) {
            if (lower.contains("/" + folder.getKey() + "/")) {
                return folder.getValue();
            }
        }
        return null;
    }
}
