package dev.moon.hotwire.switching;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;

public final class WaitingCatchUps {
    private volatile boolean holding;
    private final List<IntSupplier> waiting = new ArrayList<>();

    public void hold(boolean hold) {
        if (hold) {
            this.clear();
        }
        this.holding = hold;
    }

    public synchronized void clear() {
        this.waiting.clear();
    }

    @Nullable Consumer<IntSupplier> findHolder() {
        return this.holding ? this::add : null;
    }

    private synchronized void add(IntSupplier catchUp) {
        this.waiting.add(catchUp);
    }

    public synchronized int run() {
        int failed = 0;
        for (IntSupplier catchUp : this.waiting) {
            failed += catchUp.getAsInt();
        }
        this.waiting.clear();
        return failed;
    }
}
