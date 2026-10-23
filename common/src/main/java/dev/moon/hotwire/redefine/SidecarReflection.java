package dev.moon.hotwire.redefine;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class SidecarReflection {
    private SidecarReflection() {
    }

    public static Method getDeclaredMethod(Class<?> type, String name, Class<?>... params)
            throws NoSuchMethodException {
        try {
            return type.getDeclaredMethod(name, params);
        } catch (NoSuchMethodException absent) {
            Method added = findAddedMethod(type, name, params);
            if (added == null) {
                throw absent;
            }
            return added;
        }
    }

    public static Method getMethod(Class<?> type, String name, Class<?>... params) throws NoSuchMethodException {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException absent) {
            for (Class<?> at = type; at != null; at = at.getSuperclass()) {
                Method added = findAddedMethod(at, name, params);
                if (added != null) {
                    return added;
                }
            }
            throw absent;
        }
    }

    public static Method[] getDeclaredMethods(Class<?> type) {
        List<Method> all = new ArrayList<>(Arrays.asList(type.getDeclaredMethods()));
        all.addAll(findAddedMethods(type));
        return all.toArray(Method[]::new);
    }

    public static Method[] getMethods(Class<?> type) {
        List<Method> all = new ArrayList<>(Arrays.asList(type.getMethods()));
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            all.addAll(findAddedMethods(at));
        }
        return all.toArray(Method[]::new);
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    public static @Nullable Object invoke(Method method, @Nullable Object receiver, Object @Nullable ... args)
            throws IllegalAccessException, InvocationTargetException {
        Object[] given = args == null ? new Object[0] : args;
        Class<?> owner = method.getDeclaringClass();
        if (owner.isHidden() && Linker.standsForInstanceMethod(owner, method.getName() + getDescriptorOf(method))) {
            @Nullable Object[] all = new @Nullable Object[given.length + 1];
            all[0] = receiver;
            System.arraycopy(given, 0, all, 1, given.length);
            return method.invoke(null, all);
        }
        try {
            return method.invoke(receiver, given);
        } catch (IllegalAccessException refused) {
            MethodHandle handle = openAsCaller(refused, lookup -> lookup.unreflect(method));
            List<@Nullable Object> all = new ArrayList<>();
            if (!Modifier.isStatic(method.getModifiers())) {
                all.add(receiver);
            }
            all.addAll(Arrays.asList(given));
            try {
                return handle.invokeWithArguments(all);
            } catch (Throwable thrown) {
                throw new InvocationTargetException(thrown);
            }
        }
    }

    public static Field getDeclaredField(Class<?> type, String name) throws NoSuchFieldException {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException absent) {
            Field added = findAddedField(type, name);
            if (added == null) {
                throw absent;
            }
            return added;
        }
    }

    public static Field getField(Class<?> type, String name) throws NoSuchFieldException {
        try {
            return type.getField(name);
        } catch (NoSuchFieldException absent) {
            for (Class<?> at = type; at != null; at = at.getSuperclass()) {
                Field added = findAddedField(at, name);
                if (added != null) {
                    return added;
                }
            }
            throw absent;
        }
    }

    public static Field[] getDeclaredFields(Class<?> type) {
        List<Field> all = new ArrayList<>(Arrays.asList(type.getDeclaredFields()));
        Class<?> holder = FieldHolders.findHolderClassOf(type);
        if (holder != null) {
            all.addAll(Arrays.asList(holder.getDeclaredFields()));
        }
        return all.toArray(Field[]::new);
    }

    public static Field[] getFields(Class<?> type) {
        List<Field> all = new ArrayList<>(Arrays.asList(type.getFields()));
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            Class<?> holder = FieldHolders.findHolderClassOf(at);
            if (holder != null) {
                all.addAll(Arrays.asList(holder.getDeclaredFields()));
            }
        }
        return all.toArray(Field[]::new);
    }

    public static @Nullable Object get(Field field, @Nullable Object receiver) throws IllegalAccessException {
        Field current = FieldHolders.getCurrent(field);
        Object target = findTarget(field, receiver);
        try {
            return current.get(target);
        } catch (IllegalAccessException refused) {
            MethodHandle getter = openAsCaller(refused, lookup -> lookup.unreflectGetter(current));
            return runOpened(Modifier.isStatic(current.getModifiers()) ? List.of() : Arrays.asList(target), getter);
        }
    }

    public static void set(Field field, @Nullable Object receiver, @Nullable Object value)
            throws IllegalAccessException {
        Field current = FieldHolders.getCurrent(field);
        Object target = findTarget(field, receiver);
        try {
            current.set(target, value);
        } catch (IllegalAccessException refused) {
            MethodHandle setter = openAsCaller(refused, lookup -> lookup.unreflectSetter(current));
            runOpened(Modifier.isStatic(current.getModifiers()) ? Arrays.asList(value) : Arrays.asList(target, value),
                    setter);
        }
    }

    public static int getInt(Field field, @Nullable Object receiver) throws IllegalAccessException {
        try {
            return FieldHolders.getCurrent(field).getInt(findTarget(field, receiver));
        } catch (IllegalAccessException refused) {
            return ((Number) Objects.requireNonNull(get(field, receiver))).intValue();
        }
    }

    public static long getLong(Field field, @Nullable Object receiver) throws IllegalAccessException {
        try {
            return FieldHolders.getCurrent(field).getLong(findTarget(field, receiver));
        } catch (IllegalAccessException refused) {
            return ((Number) Objects.requireNonNull(get(field, receiver))).longValue();
        }
    }

    public static boolean getBoolean(Field field, @Nullable Object receiver) throws IllegalAccessException {
        try {
            return FieldHolders.getCurrent(field).getBoolean(findTarget(field, receiver));
        } catch (IllegalAccessException refused) {
            return (Boolean) Objects.requireNonNull(get(field, receiver));
        }
    }

    public static double getDouble(Field field, @Nullable Object receiver) throws IllegalAccessException {
        try {
            return FieldHolders.getCurrent(field).getDouble(findTarget(field, receiver));
        } catch (IllegalAccessException refused) {
            return ((Number) Objects.requireNonNull(get(field, receiver))).doubleValue();
        }
    }

    public static float getFloat(Field field, @Nullable Object receiver) throws IllegalAccessException {
        try {
            return FieldHolders.getCurrent(field).getFloat(findTarget(field, receiver));
        } catch (IllegalAccessException refused) {
            return ((Number) Objects.requireNonNull(get(field, receiver))).floatValue();
        }
    }

    public static void setInt(Field field, @Nullable Object receiver, int value) throws IllegalAccessException {
        try {
            FieldHolders.getCurrent(field).setInt(findTarget(field, receiver), value);
        } catch (IllegalAccessException refused) {
            set(field, receiver, value);
        }
    }

    public static void setLong(Field field, @Nullable Object receiver, long value) throws IllegalAccessException {
        try {
            FieldHolders.getCurrent(field).setLong(findTarget(field, receiver), value);
        } catch (IllegalAccessException refused) {
            set(field, receiver, value);
        }
    }

    public static void setBoolean(Field field, @Nullable Object receiver, boolean value)
            throws IllegalAccessException {
        try {
            FieldHolders.getCurrent(field).setBoolean(findTarget(field, receiver), value);
        } catch (IllegalAccessException refused) {
            set(field, receiver, value);
        }
    }

    public static void setDouble(Field field, @Nullable Object receiver, double value) throws IllegalAccessException {
        try {
            FieldHolders.getCurrent(field).setDouble(findTarget(field, receiver), value);
        } catch (IllegalAccessException refused) {
            set(field, receiver, value);
        }
    }

    public static void setFloat(Field field, @Nullable Object receiver, float value) throws IllegalAccessException {
        try {
            FieldHolders.getCurrent(field).setFloat(findTarget(field, receiver), value);
        } catch (IllegalAccessException refused) {
            set(field, receiver, value);
        }
    }

    public static boolean isInstance(Class<?> type, @Nullable Object object) {
        return type.isInstance(object) || Linker.carries(type.getName().replace('.', '/'), object);
    }

    public static @Nullable Object cast(Class<?> type, @Nullable Object object) {
        if (object == null || isInstance(type, object)) {
            return object;
        }
        throw new ClassCastException("Cannot cast " + object.getClass().getName() + " to " + type.getName());
    }

    public static boolean isAssignableFrom(Class<?> type, Class<?> other) {
        return type.isAssignableFrom(other) || DuckCalls.carries(type.getName().replace('.', '/'), other);
    }

    private interface Unreflect {
        MethodHandle open(MethodHandles.Lookup lookup) throws IllegalAccessException;
    }

    private static MethodHandle openAsCaller(IllegalAccessException refused, Unreflect unreflect)
            throws IllegalAccessException {
        Class<?> caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .filter(type -> type != SidecarReflection.class).findFirst().orElse(null));
        if (caller == null) {
            throw refused;
        }
        try {
            return unreflect.open(MethodHandles.privateLookupIn(caller, MethodHandles.lookup()));
        } catch (IllegalAccessException notAllowed) {
            throw refused;
        }
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    private static @Nullable Object runOpened(List<@Nullable Object> args, MethodHandle handle) {
        try {
            return handle.invokeWithArguments(args);
        } catch (RuntimeException | Error thrown) {
            throw thrown;
        } catch (Throwable thrown) {
            throw new IllegalStateException(thrown);
        }
    }

    private static @Nullable Object findTarget(Field field, @Nullable Object receiver) {
        if (receiver == null || Modifier.isStatic(field.getModifiers())
                || !FieldHolders.isHolderClass(field.getDeclaringClass())) {
            return receiver;
        }
        return FieldHolders.getHolderOf(field.getDeclaringClass(), receiver);
    }

    private static @Nullable Method findAddedMethod(Class<?> type, String name, Class<?>[] params) {
        MethodHandles.Lookup side = Linker.findSidecar(type);
        if (side == null) {
            return null;
        }
        Class<?>[] withSelf = new Class<?>[params.length + 1];
        withSelf[0] = type;
        System.arraycopy(params, 0, withSelf, 1, params.length);
        try {
            return side.lookupClass().getDeclaredMethod(name, withSelf);
        } catch (NoSuchMethodException instanceAbsent) {
            try {
                return side.lookupClass().getDeclaredMethod(name, params);
            } catch (NoSuchMethodException staticAbsent) {
                return null;
            }
        }
    }

    private static @Nullable Field findAddedField(Class<?> type, String name) {
        Class<?> holder = FieldHolders.findHolderClassOf(type);
        if (holder == null) {
            return null;
        }
        try {
            return holder.getDeclaredField(name);
        } catch (NoSuchFieldException absent) {
            return null;
        }
    }

    private static List<Method> findAddedMethods(Class<?> type) {
        MethodHandles.Lookup side = Linker.findSidecar(type);
        return side == null ? List.of() : Arrays.asList(side.lookupClass().getDeclaredMethods());
    }

    private static String getDescriptorOf(Method method) {
        StringBuilder out = new StringBuilder("(");
        for (Class<?> param : method.getParameterTypes()) {
            out.append(param.descriptorString());
        }
        return out.append(')').append(method.getReturnType().descriptorString()).toString();
    }
}
