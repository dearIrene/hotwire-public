package dev.moon.hotwire.registration;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public final class CallerOwner {
    public record Owner(String mod, @Nullable String via) {
    }

    private static final Pattern MIXIN_UID = Pattern.compile("(?!lambda)[a-z]{3}[0-9a-f]{3}");

    private final Function<Class<?>, @Nullable String> modOf;
    private final Set<String> skipped;
    private final Set<String> libraries;

    public CallerOwner(Function<Class<?>, @Nullable String> modOf, Set<String> skipped, Set<String> libraries) {
        this.modOf = modOf;
        this.skipped = Set.copyOf(skipped);
        this.libraries = Set.copyOf(libraries);
    }

    public record Frame(Class<?> type, String method) {
    }

    @Nullable Owner find(List<Class<?>> stack) {
        return this.findFrames(stack.stream().map(type -> new Frame(type, "")).toList());
    }

    public @Nullable Owner findFrames(List<Frame> stack) {
        String firstLibrary = null;
        for (Frame frame : stack) {
            String handler = findModInHandlerName(frame.method());
            String mod = handler != null ? handler : this.modOf.apply(frame.type());
            if (mod == null || this.skipped.contains(mod)) {
                continue;
            }

            if (handler != null) {
                if (firstLibrary == null) {
                    return new Owner(mod, null);
                }
                continue;
            }
            if (!this.libraries.contains(mod)) {
                return new Owner(mod, firstLibrary);
            }
            if (firstLibrary == null) {
                firstLibrary = mod;
            }
        }
        return firstLibrary == null ? null : new Owner(firstLibrary, null);
    }

    static @Nullable String findModInHandlerName(String method) {
        int first = method.indexOf('$');
        int second = first < 0 ? -1 : method.indexOf('$', first + 1);
        int third = second < 0 ? -1 : method.indexOf('$', second + 1);
        if (third <= second + 1 || !MIXIN_UID.matcher(method.substring(first + 1, second)).matches()) {
            return null;
        }
        return method.substring(second + 1, third);
    }
}
