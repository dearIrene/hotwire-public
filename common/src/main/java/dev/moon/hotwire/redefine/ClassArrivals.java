package dev.moon.hotwire.redefine;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

public final class ClassArrivals {
    private static final int MOST = 4096;
    private final Queue<NewClass> arrived = new ConcurrentLinkedQueue<>();
    private final AtomicInteger count = new AtomicInteger();
    private volatile boolean listening;

    private record NewClass(String binaryName, ClassLoader loader) {
    }

    ClassArrivals() {
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

    public void listen() {
        this.arrived.clear();
        this.count.set(0);
        this.listening = true;
    }

    public @Nullable List<Class<?>> take() {
        if (!this.listening) {
            return null;
        }
        List<Class<?>> found = new ArrayList<>();
        for (NewClass defined = this.arrived.poll(); defined != null; defined = this.arrived.poll()) {
            this.count.decrementAndGet();
            try {
                found.add(Class.forName(defined.binaryName(), false, defined.loader()));
            } catch (ClassNotFoundException | LinkageError e) {
            }
        }
        return found;
    }
}
