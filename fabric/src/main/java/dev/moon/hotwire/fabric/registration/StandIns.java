package dev.moon.hotwire.fabric.registration;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;

final class StandIns {
    private StandIns() {
    }

    static boolean fits(Class<?> face) {
        if (!face.isInterface()) {
            return false;
        }
        for (Method method : face.getMethods()) {
            Class<?> type = method.getReturnType();
            boolean nothing = type == void.class || type.isPrimitive() || type == Optional.class
                    || type == OptionalInt.class || type == OptionalLong.class || type == OptionalDouble.class;
            if (!method.isDefault() && !Modifier.isStatic(method.getModifiers()) && !nothing) {
                return false;
            }
        }
        return true;
    }

    static Object of(Class<?> face) {
        InvocationHandler nothing = (proxy, method, arguments) -> answer(proxy, method, arguments);
        return Proxy.newProxyInstance(face.getClassLoader(), new Class<?>[] {face}, nothing);
    }

    @SuppressWarnings("ReferenceEquality")
    private static @Nullable Object answer(Object proxy, Method method, Object @Nullable [] arguments)
            throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> arguments != null && arguments.length == 1 && arguments[0] == proxy;
                case "hashCode" -> System.identityHashCode(proxy);
                default -> "a stand-in for an off mod's " + proxy.getClass().getInterfaces()[0].getName();
            };
        }
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, arguments);
        }
        return makeEmpty(method.getReturnType());
    }

    private static @Nullable Object makeEmpty(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == Optional.class) {
            return Optional.empty();
        }
        if (type == OptionalInt.class) {
            return OptionalInt.empty();
        }
        if (type == OptionalLong.class) {
            return OptionalLong.empty();
        }
        if (type == OptionalDouble.class) {
            return OptionalDouble.empty();
        }
        return null;
    }
}
