package dev.moon.hotwire.fabric.registration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ElementLook {
    private static final Logger LOGGER = LoggerFactory.getLogger(ElementLook.class);

    private static final long STANDS_NANOS = 120_000_000_000L;

    private static final long FRESH_NANOS = 15_000_000_000L;

    private static volatile boolean isOnlyLooking;

    private static final Object ONE_LOOK = new Object();
    private static volatile List<String> lookedFor = List.of();
    private static volatile long lookedAt;

    private @Nullable List<Runnable> noted;
    private List<String> mods = List.of();
    private long at;

    public static void findAhead(FieldReferences references, Collection<String> mods, Predicate<String> on) {
        synchronized (ONE_LOOK) {
            List<String> asked = List.copyOf(mods);
            if (asked.equals(lookedFor) && System.nanoTime() - lookedAt < FRESH_NANOS) {
                return;
            }
            isOnlyLooking = true;
            try {
                GameHold.runUnheld(() -> references.takeOutElementsAgain(mods, on));
                lookedFor = asked;
                lookedAt = System.nanoTime();
            } catch (RuntimeException | LinkageError e) {
                LOGGER.info("[Hotwire] the look ahead for {} ended early: {}", mods, e.toString());
            } finally {
                isOnlyLooking = false;
            }
        }
    }

    @Nullable List<Runnable> open(List<String> walked) {
        List<Runnable> ahead = !isOnlyLooking && this.mods.containsAll(walked)
                && System.nanoTime() - this.at < STANDS_NANOS ? this.noted : null;
        this.noted = isOnlyLooking ? new ArrayList<>() : null;
        this.mods = isOnlyLooking ? List.copyOf(walked) : List.of();
        this.at = System.nanoTime();
        if (!isOnlyLooking) {
            lookedFor = List.of();
        }
        return ahead;
    }

    boolean hasNoted(Runnable work) {
        List<Runnable> noting = this.noted;
        if (noting != null) {
            noting.add(work);
        }
        return noting != null;
    }

    void drop() {
        this.noted = null;
        this.mods = List.of();
    }
}
