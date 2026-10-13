package dev.moon.hotwire.mixin_copy.copy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.ClassInfo;
import org.spongepowered.asm.mixin.transformer.Config;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.service.MixinServiceAbstract;
import org.spongepowered.asm.util.Locals;

final class CopyMixinInternals {
    private static final String TRANSFORMER = "org.spongepowered.asm.mixin.transformer.";

    record Start(Method gotoPhase, Field processor, Field sessionId, Field coprocessors, Field accessorSessionId,
            Field sideName) {
    }

    record Reset(Field activeTransformer, Field registeredConfigs, Field allConfigs, Field globalMixinList,
            Field configOrder, Field mixinOrder, Field classInfoCache, Field mapperClasses, Field mapperMethods,
            Field localVariables, Field extensions, Field classInfoMixins, Field inheritanceTracker,
            Field parentMixins) {
    }

    record Order(Method checkSelect, Field configs, Field mixins, Field className, Field order) {
    }

    private static @Nullable Start start;
    private static @Nullable Reset reset;
    private static @Nullable Order order;

    private CopyMixinInternals() {
    }

    static synchronized Start start() {
        Start bound = start;
        if (bound == null) {
            bound = new Start(getMethod(MixinEnvironment.class, "gotoPhase", MixinEnvironment.Phase.class),
                    getField(loadClass(TRANSFORMER + "MixinTransformer"), "processor"),
                    getField(loadClass(TRANSFORMER + "MixinProcessor"), "sessionId"),
                    getField(loadClass(TRANSFORMER + "MixinProcessor"), "coprocessors"),
                    getField(loadClass(TRANSFORMER + "MixinCoprocessorAccessor"), "sessionId"),
                    getField(MixinServiceAbstract.class, "sideName"));
            start = bound;
        }
        return bound;
    }

    static synchronized Reset reset() {
        Reset bound = reset;
        if (bound == null) {
            Class<?> config = loadClass(TRANSFORMER + "MixinConfig");
            Class<?> mapper = loadClass(TRANSFORMER + "MethodMapper");
            bound = new Reset(getField(MixinEnvironment.class, "transformer"),
                    getField(Mixins.class, "registeredConfigs"),
                    getField(Config.class, "allConfigs"), getField(config, "globalMixinList"), getField(config,
                    "configOrder"),
                    getField(loadClass(TRANSFORMER + "MixinInfo"), "mixinOrder"), getField(ClassInfo.class, "cache"),
                    getField(mapper, "classes"), getField(mapper, "methods"), getField(Locals.class,
                    "calculatedLocalVariables"),
                    getField(Extensions.class, "extensions"), getField(ClassInfo.class, "mixins"),
                    getField(loadClass(TRANSFORMER + "MixinInheritanceTracker"), "INSTANCE"),
                    getField(loadClass(TRANSFORMER + "MixinInheritanceTracker"), "parentMixins"));
            reset = bound;
        }
        return bound;
    }

    static synchronized Order order() {
        Order bound = order;
        if (bound == null) {
            Class<?> processor = loadClass(TRANSFORMER + "MixinProcessor");
            Class<?> mixin = loadClass(TRANSFORMER + "MixinInfo");
            bound = new Order(getMethod(processor, "checkSelect", MixinEnvironment.class),
                    getField(processor, "configs"), getField(loadClass(TRANSFORMER + "MixinConfig"), "mixins"),
                    getField(mixin, "className"), getField(mixin, "order"));
            order = bound;
        }
        return bound;
    }

    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name, false, CopyMixinInternals.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Mixin " + MixinBootstrap.VERSION + " has no class " + name, e);
        }
    }

    private static Field getField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException | RuntimeException e) {
            throw new IllegalStateException("Mixin " + MixinBootstrap.VERSION + " has no field " + owner.getName()
                    + "." + name, e);
        }
    }

    private static Method getMethod(Class<?> owner, String name, Class<?>... parameters) {
        try {
            Method method = owner.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException | RuntimeException e) {
            throw new IllegalStateException("Mixin " + MixinBootstrap.VERSION + " has no method " + owner.getName()
                    + "." + name, e);
        }
    }
}
