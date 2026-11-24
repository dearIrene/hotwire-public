package dev.moon.hotwire.mixin_copy;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public final class MixinFailures {
    private static final Pattern NO_TARGET = Pattern.compile("@Mixin target (\\S+) was not found");

    private MixinFailures() {
    }

    public static String describe(Throwable failure) {
        for (Throwable at = failure; at != null; at = at.getCause()) {
            String target = findMissingTarget(at.getMessage());
            if (target != null) {
                return "it changes " + target + ", which this game doesn't have. It was made for another version"
                        + " of the mod that has that class";
            }
        }
        return String.valueOf(failure.getMessage());
    }

    static @Nullable String findMissingTarget(@Nullable String message) {
        if (message == null) {
            return null;
        }
        Matcher found = NO_TARGET.matcher(message);
        return found.find() ? found.group(1) : null;
    }
}
