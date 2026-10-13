package dev.moon.hotwire.concurrent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Tasks implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(Tasks.class);

    private final ExecutorService workers;
    private final Executor gameThread;
    private final BiConsumer<String, Throwable> onFailure;

    public Tasks(int workers, Executor gameThread) {
        this(workers, gameThread, (name, error) -> LOGGER.error("[Hotwire] {} failed", name, error));
    }

    public Tasks(int workers, Executor gameThread, BiConsumer<String, Throwable> onFailure) {
        this.workers = Executors.newFixedThreadPool(workers, Thread.ofPlatform().name("hotwire-worker-", 0).daemon(
                ).factory());
        this.gameThread = gameThread;
        this.onFailure = onFailure;
    }

    public <T> CompletableFuture<T> submit(String name, Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, this.workers).whenComplete((result, error) -> {
            if (error != null) {
                this.onFailure.accept(name, error instanceof CompletionException wrapped && wrapped.getCause() != null
                        ? wrapped.getCause() : error);
            }
        });
    }

    public <T> CompletableFuture<Void> submit(String name, Supplier<T> work, Consumer<? super T> onGameThread) {
        return this.submit(name, work).thenAcceptAsync(result -> {
            try {
                onGameThread.accept(result);
            } catch (RuntimeException | LinkageError e) {
                this.onFailure.accept(name, e);
                throw e;
            }
        }, this.gameThread);
    }

    @Override
    public void close() {
        this.workers.shutdownNow();
    }
}
