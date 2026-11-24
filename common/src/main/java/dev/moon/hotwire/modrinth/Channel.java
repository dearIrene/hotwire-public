package dev.moon.hotwire.modrinth;

import java.util.List;
import java.util.function.Supplier;

public final class Channel {
    private static volatile Supplier<List<String>> allowed = () -> List.of("release");

    private Channel() {
    }

    public static void setAllowed(Supplier<List<String>> types) {
        allowed = types;
    }

    public static List<String> findAllowed() {
        return allowed.get();
    }

    public static boolean allows(ModrinthClient.Version version) {
        return findAllowed().contains(version.type());
    }

    public static ModrinthClient.Version pick(List<ModrinthClient.Version> newestFirst) {
        return newestFirst.stream().filter(Channel::allows).findFirst().orElse(newestFirst.getFirst());
    }
}
