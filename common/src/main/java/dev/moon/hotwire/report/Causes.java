package dev.moon.hotwire.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public final class Causes {
    private static final int DEEPEST = 32;

    private Causes() {
    }

    public static List<Throwable> list(Throwable thrown) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable at = thrown; at != null && chain.size() < DEEPEST && seen.add(at); at = at.getCause()) {
            chain.add(at);
        }
        return chain;
    }

    public static Throwable findInnermost(Throwable thrown) {
        return list(thrown).getLast();
    }

    public static boolean hasCause(Throwable thrown, Class<? extends Throwable> type) {
        return list(thrown).stream().anyMatch(type::isInstance);
    }
}
