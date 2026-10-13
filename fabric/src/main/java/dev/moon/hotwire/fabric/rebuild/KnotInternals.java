package dev.moon.hotwire.fabric.rebuild;

import dev.moon.hotwire.jvm.PrivateField;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Function;
import java.util.function.Predicate;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.jspecify.annotations.Nullable;

public final class KnotInternals {
    private final Function<String, byte @Nullable []> preMixin;
    private final Predicate<String> loaded;

    private KnotInternals(Function<String, byte @Nullable []> preMixin, Predicate<String> loaded) {
        this.preMixin = preMixin;
        this.loaded = loaded;
    }

    @SuppressWarnings("unchecked")
    public static KnotInternals bind() {
        FabricLauncher launcher = FabricLauncherBase.getLauncher();
        Object delegate = PrivateField.of(launcher.getClass(), "classLoader").get(launcher, Object.class);
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(delegate.getClass(), MethodHandles.lookup());
            MethodHandle bytes = lookup.findVirtual(delegate.getClass(), "getPreMixinClassBytes",
                    MethodType.methodType(byte[].class, String.class)).bindTo(delegate);
            MethodHandle isLoaded = lookup.findVirtual(delegate.getClass(), "isClassLoaded",
                    MethodType.methodType(boolean.class, String.class)).bindTo(delegate);
            return new KnotInternals(MethodHandleProxies.asInterfaceInstance(Function.class, bytes),
                    MethodHandleProxies.asInterfaceInstance(Predicate.class, isLoaded));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException("[Hotwire] cannot reach Knot's class bytes in "
                    + delegate.getClass().getName() + ": " + e, e);
        }
    }

    boolean isLoaded(String internal) {
        return this.loaded.test(internal.replace('/', '.'));
    }

    byte @Nullable [] findPreMixinBytes(String internal) {
        return this.preMixin.apply(internal.replace('/', '.'));
    }
}
