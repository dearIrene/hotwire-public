package dev.moon.hotwire.concurrent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class Parallel {
    private Parallel() {
    }

    public static <T> void forEach(Collection<T> items, Consumer<T> work) {
        filter(new ArrayList<>(items), item -> {
            work.accept(item);
            return false;
        });
    }

    public static <T> List<T> filter(List<T> items, Predicate<T> test) {
        int size = items.size();
        int parts = Math.min(size, ForkJoinPool.getCommonPoolParallelism() * 4);
        List<T> matched = new ArrayList<>();
        if (parts <= 1) {
            for (T item : items) {
                if (test.test(item)) {
                    matched.add(item);
                }
            }
            return matched;
        }
        List<ForkJoinTask<List<T>>> tasks = new ArrayList<>(parts);
        for (int part = 0; part < parts; part++) {
            int from = part * size / parts;
            int to = (part + 1) * size / parts;
            tasks.add(ForkJoinPool.commonPool().submit(() -> {
                List<T> run = new ArrayList<>();
                for (int at = from; at < to; at++) {
                    T item = items.get(at);
                    if (test.test(item)) {
                        run.add(item);
                    }
                }
                return run;
            }));
        }
        for (ForkJoinTask<List<T>> task : tasks) {
            matched.addAll(task.join());
        }
        return matched;
    }
}
