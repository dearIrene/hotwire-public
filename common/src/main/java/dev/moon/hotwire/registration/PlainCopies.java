package dev.moon.hotwire.registration;

import dev.moon.hotwire.classfile.GameNames;
import dev.moon.hotwire.redefine.Adoption;
import dev.moon.hotwire.redefine.FinalWrites;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

final class PlainCopies {
    private PlainCopies() {
    }

    static @Nullable Object of(Object value, Class<?> slot) {
        Class<?> base = value.getClass().getSuperclass();
        while (base != null && !GameNames.isMinecraft(base.getName())) {
            if (base == Object.class) {
                return null;
            }
            base = base.getSuperclass();
        }
        if (base == null || Modifier.isAbstract(base.getModifiers()) || !slot.isAssignableFrom(base)) {
            return null;
        }
        try {
            List<Field> fields = new ArrayList<>();
            for (Class<?> at = base; at != null && at != Object.class; at = at.getSuperclass()) {
                for (Field field : at.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    field.setAccessible(true);
                    if (!field.getType().isPrimitive() && field.get(value) == null) {
                        return null;
                    }
                    fields.add(field);
                }
            }
            Constructor<?> bare = Adoption.findFactory(base, Object.class.getDeclaredConstructor());
            Object copy = bare.newInstance();
            for (Field field : fields) {
                FinalWrites.write(field, copy, field.get(value));
            }
            return copy;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
