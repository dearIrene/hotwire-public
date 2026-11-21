package dev.moon.hotwire.engine;

import dev.moon.hotwire.report.Causes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public final class DataPackFaults {
    private static final Pattern FROM_PACK = Pattern.compile("from pack (\\S+)");

    private static final long THROW_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static volatile List<String> packs = List.of();
    private static volatile long notedAt;

    private static volatile @Nullable String faulted;
    private static volatile @Nullable Consumer<String> whenStopped;

    private DataPackFaults() {
    }

    public static void note(Collection<? extends Throwable> errors) {
        List<String> found = new ArrayList<>();
        for (Throwable error : errors) {
            for (Throwable at : Causes.list(error)) {
                Matcher pack = FROM_PACK.matcher(String.valueOf(at.getMessage()));
                while (pack.find()) {
                    if (!found.contains(pack.group(1))) {
                        found.add(pack.group(1));
                    }
                }
            }
        }
        packs = List.copyOf(found);
        notedAt = System.nanoTime();
    }

    static @Nullable String findModOf(Predicate<String> canLeave) {
        boolean recent = System.nanoTime() - notedAt < THROW_NANOS;
        String mod = recent ? packs.stream().filter(canLeave).findFirst().orElse(null) : null;
        faulted = mod;
        return mod;
    }

    public static @Nullable String findRecentMod(Predicate<String> isMod) {
        boolean recent = System.nanoTime() - notedAt < THROW_NANOS;
        return recent ? packs.stream().filter(isMod).findFirst().orElse(null) : null;
    }

    static boolean isFaulted(String mod) {
        return mod.equals(faulted);
    }

    public static void onStopped(Consumer<String> shown) {
        whenStopped = shown;
    }

    static void tellStopped(String name) {
        packs = List.of();
        faulted = null;
        Consumer<String> shown = whenStopped;
        if (shown != null) {
            shown.accept(name);
        }
    }
}
