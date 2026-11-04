package dev.moon.hotwire.fabric.rebuild;

import dev.moon.hotwire.redefine.Tripwire;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LiveCancellers {
    private static final Logger LOGGER = LoggerFactory.getLogger(LiveCancellers.class);
    private static final String REGISTRAR = "com.bawnorton.mixinsquared.canceller.MixinCancellerRegistrar";

    private LiveCancellers() {
    }

    public static boolean shouldCancel(List<String> targets, String mixin) {
        boolean before = Tripwire.markHotwireWork(true);
        try {
            return askRegistrar(targets, mixin) || askEntrypoints(targets, mixin);
        } finally {
            Tripwire.markHotwireWork(before);
        }
    }

    private static boolean askRegistrar(List<String> targets, String mixin) {
        try {
            Method ask = Class.forName(REGISTRAR, true, LiveCancellers.class.getClassLoader())
                    .getMethod("shouldCancel", List.class, String.class, Consumer.class);
            return Boolean.TRUE.equals(ask.invoke(null, targets, mixin, (Consumer<String>) name -> { }));
        } catch (ClassNotFoundException absent) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("[Hotwire] MixinSquared's cancellers could not be asked about {}: {}", mixin, e.toString());
            return false;
        }
    }

    private static boolean askEntrypoints(List<String> targets, String mixin) {
        for (EntrypointContainer<Object> container : FabricLoader.getInstance()
                .getEntrypointContainers("mixinsquared", Object.class)) {
            try {
                Object entry = container.getEntrypoint();
                Method ask = findAsk(entry);
                if (ask != null && Boolean.TRUE.equals(ask.invoke(entry, targets, mixin))) {
                    return true;
                }
            } catch (InvocationTargetException | IllegalAccessException | RuntimeException | LinkageError e) {
                LOGGER.warn("[Hotwire] {}'s canceller could not be asked about {}: {}",
                        container.getProvider().getMetadata().getId(), mixin, e.toString());
            }
        }
        return false;
    }

    private static @Nullable Method findAsk(Object entry) {
        try {
            return entry.getClass().getMethod("shouldCancel", List.class, String.class);
        } catch (NoSuchMethodException none) {
            return null;
        }
    }
}
