package dev.moon.hotwire.fabric.update;

import dev.moon.hotwire.fabric.registration.Owners;
import dev.moon.hotwire.redefine.InitialValues;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OldStatics {
    private static final Logger LOGGER = LoggerFactory.getLogger(OldStatics.class);

    private OldStatics() {
    }

    public static Runnable takeOut(Set<String> leaving, Class<?>[] loaded, Predicate<Class<?>> initialized,
            Function<String, byte @Nullable []> originalBytes) {
        ClassLoader game = OldStatics.class.getClassLoader();
        List<Runnable> back = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        for (Class<?> owner : loaded) {
            String name = Type.getInternalName(owner);
            if (!Objects.equals(owner.getClassLoader(), game) || owner.isHidden() || leaving.contains(name)
                    || name.startsWith("dev/moon/hotwire/") || Owners.isOfOffMod(owner)
                    || !initialized.test(owner)) {
                continue;
            }
            try {
                for (Field field : owner.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())
                            || field.getType().isPrimitive()) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object old = field.get(null);
                    if (old == null || !leaving.contains(Type.getInternalName(old.getClass()))) {
                        continue;
                    }
                    byte[] original = originalBytes.apply(name);
                    Optional<Object> first = original == null ? Optional.empty() : InitialValues.ofStatic(owner,
                            original, field.getName(), field.getType().descriptorString());
                    field.set(null, first.filter(value -> !leaving.contains(value.getClass().getName()
                            .replace('.', '/'))).orElse(null));
                    back.add(() -> {
                        try {
                            field.set(null, old);
                        } catch (IllegalAccessException | RuntimeException e) {
                            LOGGER.warn("[Hotwire] {} could not be given the old version's object back", field, e);
                        }
                    });
                    fields.add(owner.getSimpleName() + "." + field.getName());
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOGGER.info("[Hotwire] {} was not looked into for the old versions' objects: {}", owner.getName(),
                        e.toString());
            }
        }
        if (!fields.isEmpty()) {
            LOGGER.info("[Hotwire] the old versions' objects left the static fields {}", fields);
        }
        return () -> back.forEach(Runnable::run);
    }
}
