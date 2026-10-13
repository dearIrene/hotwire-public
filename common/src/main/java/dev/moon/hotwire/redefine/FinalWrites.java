package dev.moon.hotwire.redefine;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.jspecify.annotations.Nullable;

public final class FinalWrites {
    public interface Writer {
        void write(Field field, @Nullable Object holder, @Nullable Object value) throws IllegalAccessException;
    }

    private static final MethodHandle WRITE;
    private static volatile Writer writer = (field, holder, value) -> {
        throw new IllegalAccessException("no writer of final fields is set");
    };

    static {
        try {
            WRITE = MethodHandles.lookup().findStatic(FinalWrites.class, "write",
                    MethodType.methodType(void.class, Field.class, Object.class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private FinalWrites() {
    }

    public static void writeWith(Writer by) {
        writer = by;
    }

    @SuppressWarnings("UnusedMethod")
    private static void write(Field field, @Nullable Object holder, @Nullable Object value)
            throws IllegalAccessException {
        writer.write(field, holder, value);
    }

    static boolean isShut(Field field) {
        return Modifier.isFinal(field.getModifiers())
                && (Modifier.isStatic(field.getModifiers()) || field.getDeclaringClass().isRecord());
    }

    static MethodHandle findSetter(Field field) {
        MethodHandle bound = WRITE.bindTo(field);
        return Modifier.isStatic(field.getModifiers()) ? MethodHandles.insertArguments(bound, 0, (Object) null)
                : bound;
    }
}
