package dev.moon.hotwire.redefine;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

public final class ClassArrivals {
    private static final int MOST = 4096;
    private static final Queue<Arrival> ARRIVED = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger COUNT = new AtomicInteger();
    private static volatile boolean listening;

    private record Arrival(String binaryName, ClassLoader loader) {
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
        ARRIVED.add(new Arrival(internalName.replace('/', '.'), loader));
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
        for (Arrival arrival = ARRIVED.poll(); arrival != null; arrival = ARRIVED.poll()) {
            COUNT.decrementAndGet();
            try {
                found.add(Class.forName(arrival.binaryName(), false, arrival.loader()));
            } catch (ClassNotFoundException | LinkageError e) {
            }
        }
        return found;
    }
}
