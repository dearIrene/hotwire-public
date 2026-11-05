package dev.moon.hotwire.update;

import dev.moon.hotwire.modrinth.ModrinthClient;
import java.util.List;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

public record UrgentUpdate(String version, String message) {
    private static final String MARK = "URGENT:";

    public static @Nullable UrgentUpdate find(List<ModrinthClient.Version> versions, Predicate<String> newer) {
        for (ModrinthClient.Version version : versions) {
            if (!newer.test(version.number())) {
                continue;
            }
            String message = findMessage(version.changelog());
            if (message != null) {
                return new UrgentUpdate(version.number(), message);
            }
        }
        return null;
    }

    static @Nullable String findMessage(@Nullable String changelog) {
        if (changelog == null) {
            return null;
        }
        for (String line : changelog.lines().toList()) {
            if (isMark(line)) {
                return readWords(line).substring(MARK.length()).strip();
            }
        }
        return null;
    }

    static boolean isMark(String line) {
        return readWords(line).regionMatches(true, 0, MARK, 0, MARK.length());
    }

    private static String readWords(String line) {
        return line.strip().replaceFirst("^[-*>#\\s]+", "").replace("**", "").replace("__", "").strip();
    }
}
