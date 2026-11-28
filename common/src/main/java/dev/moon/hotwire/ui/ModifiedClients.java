package dev.moon.hotwire.ui;

import java.util.Map;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

public final class ModifiedClients {
    private static final Map<String, String> BY_MOD = Map.of("ichor", "Lunar Client", "labymod", "LabyMod");

    public static final String DAWN = "Dawn Client";

    private static final String DAWN_MOD = "dawn";
    private static final String DAWN_FILE = "mixins.feather.json";

    private static volatile @Nullable String current;
    private static volatile boolean promptDue;
    private static volatile boolean noteDue;

    private ModifiedClients() {
    }

    public static @Nullable String getCurrent() {
        return current;
    }

    public static void setCurrent(String client) {
        current = client;
        promptDue = true;
        noteDue = DAWN.equals(client);
    }

    static boolean takeNoteDue() {
        boolean due = noteDue;
        noteDue = false;
        return due;
    }

    static boolean takePromptDue() {
        boolean due = promptDue;
        promptDue = false;
        return due;
    }

    public static boolean isDawn(Predicate<String> isLoaded, Predicate<String> hasFile) {
        return isLoaded.test(DAWN_MOD) && hasFile.test(DAWN_FILE);
    }

    public static @Nullable String find(Predicate<String> isLoaded) {
        for (Map.Entry<String, String> mod : BY_MOD.entrySet()) {
            if (isLoaded.test(mod.getKey())) {
                return mod.getValue();
            }
        }
        return null;
    }
}
