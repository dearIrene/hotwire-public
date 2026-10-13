package dev.moon.hotwire.fabric.rebuild;

import java.lang.reflect.Method;
import java.util.List;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LocalRefs {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalRefs.class);
    private static final String GENERATOR = "com.llamalad7.mixinextras.sugar.impl.ref.LocalRefClassGenerator";

    private LocalRefs() {
    }

    public static void prepare() {
        try {
            Method make = Class.forName(GENERATOR).getMethod("getForType", Type.class);
            for (Type type : List.of(Type.BOOLEAN_TYPE, Type.BYTE_TYPE, Type.CHAR_TYPE, Type.SHORT_TYPE, Type.INT_TYPE,
                    Type.LONG_TYPE, Type.FLOAT_TYPE, Type.DOUBLE_TYPE, Type.getType(Object.class))) {
                make.invoke(null, type);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.info("[Hotwire] the game's MixinExtras did not make its local reference classes up front: {}",
                    e.toString());
        }
    }
}
