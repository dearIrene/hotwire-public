package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.jvm.JvmAccess;
import dev.moon.hotwire.jvm.JvmAccessException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class FutureWork {
    private static final Logger LOGGER = LoggerFactory.getLogger(FutureWork.class);
    private static final Map<Class<?>, VarHandle> WORK_OF = findHandles();

    private FutureWork() {
    }

    private static Map<Class<?>, VarHandle> findHandles() {
        Map<Class<?>, VarHandle> handles = new HashMap<>();
        try {
            MethodHandles.Lookup trusted = JvmAccess.findTrustedLookup();
            Class<?> supply = Class.forName(CompletableFuture.class.getName() + "$AsyncSupply");
            Class<?> run = Class.forName(CompletableFuture.class.getName() + "$AsyncRun");
            handles.put(supply, trusted.findVarHandle(supply, "fn", Supplier.class));
            handles.put(run, trusted.findVarHandle(run, "fn", Runnable.class));
        } catch (JvmAccessException | ReflectiveOperationException | RuntimeException e) {
            LOGGER.info("[Hotwire] what a future queued on the game's loop runs cannot be read: {}", e.toString());
        }
        return handles;
    }

    static @Nullable Object find(Object task) {
        VarHandle work = WORK_OF.get(task.getClass());
        return work == null ? null : work.get(task);
    }
}
