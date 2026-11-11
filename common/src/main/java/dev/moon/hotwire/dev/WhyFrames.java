package dev.moon.hotwire.dev;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public final class WhyFrames {
    private static final String LAMBDA = "lambda";
    private static final Pattern PART = Pattern.compile("\\$");
    private static final Pattern NUMBER = Pattern.compile("[0-9]+");
    private static final Pattern MERGED = Pattern.compile("(?:md|fd)[0-9a-f]{6}");

    private WhyFrames() {
    }

    public record Frame(String type, String method, int line) {
    }

    public static @Nullable Frame find(List<Frame> frames, Predicate<String> own,
            BiFunction<String, String, @Nullable String> mixin) {
        for (Frame frame : frames) {
            if (own.test(frame.type())) {
                return new Frame(frame.type(), foldName(frame.method()), frame.line());
            }
            String merged = mixin.apply(frame.type(), frame.method());
            if (merged != null) {
                return new Frame(merged, foldName(frame.method()), frame.line());
            }
        }
        return null;
    }

    public static String foldName(String method) {
        String[] parts = PART.split(method, -1);
        for (int at = 0; at + 1 < parts.length; at++) {
            if (parts[at].equals(LAMBDA)) {
                boolean after = at > 0 && NUMBER.matcher(parts[at + 1]).matches();
                return after ? parts[at - 1] : parts[at + 1];
            }
        }
        boolean merged = parts.length >= 3 || (parts.length == 2 && MERGED.matcher(parts[0]).matches());
        return merged ? parts[parts.length - 1] : method;
    }
}
