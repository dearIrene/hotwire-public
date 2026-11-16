package dev.moon.hotwire.add;

import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

public final class TickEnd {
    private static final AtomicReference<@Nullable Runnable> DUE = new AtomicReference<>();

    private TickEnd() {
    }

    public static void run(Runnable work) {
        DUE.set(work);
    }

    public static void runDue() {
        Runnable due = DUE.get();
        if (due != null && DUE.compareAndSet(due, null)) {
            due.run();
        }
    }
}
