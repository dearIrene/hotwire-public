package dev.moon.hotwire.switching;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

public final class Waits {
    private static final long PAUSE_MILLIS = 50;
    private final List<Wait> waiting = new ArrayList<>();
    private boolean asking;

    private record Wait(BooleanSupplier over, CompletableFuture<Void> done) {
    }

    public CompletableFuture<Void> awaitTrue(BooleanSupplier over) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        if (over.getAsBoolean()) {
            done.complete(null);
            return done;
        }
        synchronized (this.waiting) {
            this.waiting.add(new Wait(over, done));
            if (!this.asking) {
                this.asking = true;
                Thread.ofPlatform().name("hotwire-api-wait").daemon().start(this::ask);
            }
        }
        return done;
    }

    private void ask() {
        while (true) {
            try {
                Thread.sleep(PAUSE_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            List<Wait> over = new ArrayList<>();
            boolean last;
            synchronized (this.waiting) {
                this.waiting.removeIf(wait -> {
                    boolean ended;
                    try {
                        ended = wait.over().getAsBoolean();
                    } catch (RuntimeException e) {
                        ended = true;
                    }
                    if (ended) {
                        over.add(wait);
                    }
                    return ended;
                });
                last = this.waiting.isEmpty();
                if (last) {
                    this.asking = false;
                }
            }
            over.forEach(wait -> wait.done().complete(null));
            if (last) {
                return;
            }
        }
    }
}
