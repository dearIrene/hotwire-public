package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.jvm.LoadedClassList;
import dev.moon.hotwire.redefine.InitialValues;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class InterfaceDefaults {
    private static final Logger LOGGER = LoggerFactory.getLogger(InterfaceDefaults.class);
    private final Instrumentation instrumentation;
    private final Function<String, byte @Nullable []> originalBytes;
    private final Map<Class<?>, Optional<Object>> found = new ConcurrentHashMap<>();

    InterfaceDefaults(Instrumentation instrumentation, Function<String, byte @Nullable []> originalBytes) {
        this.instrumentation = instrumentation;
        this.originalBytes = originalBytes;
    }

    Optional<Object> find(Object value, String mod) {
        for (Class<?> face : value.getClass().getInterfaces()) {
            String faceMod = GameRegistrations.findModOf(face);
            if (faceMod != null && !faceMod.equals(mod)) {
                Optional<Object> start = this.found.computeIfAbsent(face, any -> this.findStartOf(face, faceMod));
                if (start.isPresent()) {
                    return start;
                }
            }
        }
        return Optional.empty();
    }

    static @Nullable String findOwnerOf(Object value) {
        Class<?> type = value.getClass();
        if (!type.isHidden()) {
            return GameRegistrations.findModOf(type);
        }
        String owner = ValueOwners.findLambdaOwner(type);
        return owner != null ? owner : GameRegistrations.findModOf(type.getNestHost());
    }

    private Optional<Object> findStartOf(Class<?> face, String faceMod) {
        List<String> tried = new ArrayList<>();
        Optional<Object> start = this.findStartOf(face, faceMod, tried);
        LOGGER.info("[Hotwire] {}'s own start value for {}: {}", faceMod, face.getName(),
                start.isPresent() ? start.get().getClass().getName() : "none, " + tried);
        return start;
    }

    private Optional<Object> findStartOf(Class<?> face, String faceMod, List<String> tried) {
        for (Class<?> type : LoadedClassList.of(this.instrumentation)) {
            if (type.isArray() || type.isPrimitive() || type.isHidden()
                    || !faceMod.equals(GameRegistrations.findModOf(type))) {
                continue;
            }
            try {
                for (Field field : type.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) || field.getType() != face) {
                        continue;
                    }
                    byte[] original = this.originalBytes.apply(Type.getInternalName(type));
                    Optional<Object> made = original == null ? Optional.empty()
                            : InitialValues.ofStatic(type, original, field.getName(), face.descriptorString());
                    if (made.isPresent() && faceMod.equals(findOwnerOf(made.get()))) {
                        return made;
                    }
                    tried.add(type.getSimpleName() + "." + field.getName() + (original == null ? " no bytes"
                            : made.isEmpty() ? " no plain start" : " of " + findOwnerOf(made.get())));
                }
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                tried.add(type.getSimpleName() + " " + e);
            }
        }
        return Optional.empty();
    }
}
