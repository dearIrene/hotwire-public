package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.lang.reflect.Field;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

final class FinalFields {
    private FinalFields() {
    }

    static Field write(Object unsafe, FieldSlots.Slot field, @Nullable Object holder, @Nullable Object value)
            throws IllegalAccessException {
        boolean isStatic = field.isStatic();
        try {
            Field made = field.asField();
            Class<?> type = unsafe.getClass();

            Object base = isStatic ? type.getMethod("staticFieldBase", Field.class).invoke(unsafe, made)
                    : Objects.requireNonNull(holder, () -> "no object to write " + made + " in");
            long offset = (long) type.getMethod(isStatic ? "staticFieldOffset" : "objectFieldOffset", Field.class)
                    .invoke(unsafe, made);
            Class<?> kind = field.getType().isPrimitive() ? field.getType() : Object.class;
            String put = "put" + (kind == Object.class ? "Reference" : Character.toUpperCase(kind.getName().charAt(0))
                    + kind.getName().substring(1)) + "Volatile";
            type.getMethod(put, Object.class, long.class, kind).invoke(unsafe, base, offset, value);
            return made;
        } catch (ReflectiveOperationException e) {
            throw new IllegalAccessException(field + " could not be written: " + e);
        }
    }
}
