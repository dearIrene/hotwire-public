package dev.moon.hotwire.switching;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

final class WorldCalls {
    private record EarlyAnswer(Set<String> mods, int switches, long at) {
    }

    private static final long FRESH_NANOS = 10_000_000_000L;
    private volatile Supplier<Set<String>> walk = Set::of;
    private volatile @Nullable EarlyAnswer ahead;

    void answerWith(Supplier<Set<String>> mods) {
        this.walk = mods;
    }

    void workOutAhead(int switches) {
        this.ahead = new EarlyAnswer(this.walk.get(), switches, System.nanoTime());
    }

    Set<String> find(int switches, Set<String> switched, Set<String> onAfter) {
        EarlyAnswer early = this.ahead;
        this.ahead = null;
        Set<String> called = early != null && early.switches() == switches - 1
                && System.nanoTime() - early.at() < FRESH_NANOS ? early.mods() : this.walk.get();
        if (Collections.disjoint(switched, onAfter)) {
            Set<String> left = new HashSet<>(called);
            left.removeAll(switched);
            this.ahead = new EarlyAnswer(Set.copyOf(left), switches, System.nanoTime());
        }
        return called;
    }
}
