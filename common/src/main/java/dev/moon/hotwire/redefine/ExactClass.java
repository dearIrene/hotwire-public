package dev.moon.hotwire.redefine;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.jspecify.annotations.Nullable;

final class ExactClass {
    private static final MethodHandle IS_CLASS;

    static {
        try {
            IS_CLASS = MethodHandles.lookup().findStatic(ExactClass.class, "isClass",
                    MethodType.methodType(boolean.class, Class.class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private ExactClass() {
    }

    static MethodHandle makeTest(Class<?> type, MethodType site) {
        return MethodHandles.dropArguments(IS_CLASS.bindTo(type).asType(MethodType.methodType(boolean.class,
                site.parameterType(0))), 1, site.parameterList().subList(1, site.parameterCount()));
    }

    @SuppressWarnings("unused")
    private static boolean isClass(Class<?> type, @Nullable Object receiver) {
        return receiver != null && receiver.getClass() == type;
    }
}
