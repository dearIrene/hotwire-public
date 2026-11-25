package dev.moon.hotwire.redefine;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

public final class ClassArrivals {
    private static final int MOST = 4096;
    private final Queue<NewClass> arrived = new ConcurrentLinkedQueue<>();
    private final AtomicInteger count = new AtomicInteger();

    private List<NewClass> unsettled = new ArrayList<>();
    private volatile boolean listening;

    private record NewClass(String binaryName, ClassLoader loader) {
    }

    private final Predicate<String> loaded;

    ClassArrivals(Predicate<String> loaded) {
        this.loaded = loaded;
    }

    public void note(String internalName, ClassLoader loader) {
        if (!this.listening) {
            return;
        }
        if (this.count.incrementAndGet() > MOST) {
            this.listening = false;
            this.arrived.clear();
            return;
        }
        this.arrived.add(new NewClass(internalName.replace('/', '.'), loader));
    }

    public synchronized void listen() {
        this.arrived.clear();
        this.unsettled = new ArrayList<>();
        this.count.set(0);
        this.listening = true;
    }

    public synchronized @Nullable List<Class<?>> take() {
        if (!this.listening) {
            return null;
        }
        List<Class<?>> found = new ArrayList<>();
        List<NewClass> again = this.unsettled;
        this.unsettled = new ArrayList<>();
        again.forEach(defined -> this.addFound(defined, found, null));
        for (NewClass defined = this.arrived.poll(); defined != null; defined = this.arrived.poll()) {
            this.count.decrementAndGet();
            this.addFound(defined, found, this.unsettled);
        }
        return found;
    }

    private void addFound(NewClass defined, List<Class<?>> found, @Nullable List<NewClass> later) {
        if (!this.loaded.test(defined.binaryName())) {
            if (later != null) {
                later.add(defined);
            }
            return;
        }
        try {
            found.add(Class.forName(defined.binaryName(), false, defined.loader()));
        } catch (ClassNotFoundException | LinkageError e) {
        }
    }
}
