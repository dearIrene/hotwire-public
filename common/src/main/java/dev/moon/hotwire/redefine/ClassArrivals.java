package dev.moon.hotwire.redefine;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

public final class ClassArrivals {
    private static final int MOST = 4096;
    private static final Queue<NewClass> ARRIVED = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger COUNT = new AtomicInteger();
    private static volatile boolean listening;

    private record NewClass(String binaryName, ClassLoader loader) {
    }

    private ClassArrivals() {
    }

    public static void note(String internalName, ClassLoader loader) {
        if (!listening) {
            return;
        }
        if (COUNT.incrementAndGet() > MOST) {
            listening = false;
            ARRIVED.clear();
            return;
        }
        ARRIVED.add(new NewClass(internalName.replace('/', '.'), loader));
    }

    public static void listen() {
        ARRIVED.clear();
        COUNT.set(0);
        listening = true;
    }

    public static @Nullable List<Class<?>> take() {
        if (!listening) {
            return null;
        }
        List<Class<?>> found = new ArrayList<>();
        for (NewClass defined = ARRIVED.poll(); defined != null; defined = ARRIVED.poll()) {
            COUNT.decrementAndGet();
            try {
                found.add(Class.forName(defined.binaryName(), false, defined.loader()));
            } catch (ClassNotFoundException | LinkageError e) {
            }
        }
        return found;
    }
}
