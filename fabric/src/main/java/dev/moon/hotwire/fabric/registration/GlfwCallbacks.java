package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.redefine.HandlerGuards;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GlfwCallbacks {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlfwCallbacks.class);
    private static final List<Method> SETTERS = findSetters();

    private record WrapperRemoval(Method setter, Object mods, Object inner) {
    }

    private record FieldRemoval(FieldSlots.Slot field, Object holder, Object mods, @Nullable Object inner) {
    }

    private static final Map<String, List<WrapperRemoval>> REMOVED = new HashMap<>();
    private static final Map<String, List<FieldRemoval>> REMOVED_FROM_FIELDS = new HashMap<>();
    private static final int BELOW = 3;
    private static volatile @Nullable Instrumentation instrumentation;

    private GlfwCallbacks() {
    }

    public static void useInstrumentation(Instrumentation found) {
        instrumentation = found;
    }

    private static @Nullable String findOwnerOf(Class<?> type) {
        return GameRegistrations.findModOrShipper(type);
    }

    public static synchronized void takeOut(Collection<String> mods) {
        GameHold.onGameAndWait(() -> {
            long window = findWindow();
            Map<String, List<Object>> objectsOf = new HashMap<>();
            for (Method setter : SETTERS) {
                Set<String> holders = findHandlersIn(setter);
                holders.removeAll(mods);
                if (!holders.isEmpty()) {
                    for (String holder : holders) {
                        List<Object> objects = objectsOf.computeIfAbsent(holder, GlfwCallbacks::findObjectsOf);
                        int out = takeOutOfFields(holder, setter.getParameterTypes()[1], mods, objects);
                        LOGGER.info("[Hotwire] {} is set through {}'s handler: {} objects of its looked through, {}"
                                + " of {}'s callbacks taken out", setter.getName(), holder, objects.size(), out, mods);
                    }
                    continue;
                }
                Object current = read(setter, window);
                String mod = current == null ? null : findOwnerOf(current.getClass());
                if (current == null || mod == null || !mods.contains(mod)) {
                    LOGGER.debug("[Hotwire] {} holds {} ({})", setter.getName(), current, mod);
                    continue;
                }
                Object inner = findWrapped(current, setter.getParameterTypes()[1], mod, 2);

                if (inner != null && set(setter, window, inner)) {
                    REMOVED.computeIfAbsent(mod, any -> new ArrayList<>()).add(new WrapperRemoval(setter, current,
                            inner));
                    LOGGER.info("[Hotwire] the window's {} is {} again instead of {}'s", setter.getName()
                            .replace("glfwSet", "").replace("Callback", " callback"),
                            inner.getClass().getName(), mod);
                }
            }
        });
    }

    private static Set<String> findHandlersIn(Method setter) {
        Set<String> mods = new HashSet<>();
        for (Method method : setter.getDeclaringClass().getDeclaredMethods()) {
            String mod = method.getName().endsWith("$" + setter.getName())
                    ? HandlerGuards.findModInHandlerName(method.getName()) : null;
            if (mod != null) {
                mods.add(mod);
            }
        }
        return mods;
    }

    private static List<Object> findObjectsOf(String holder) {
        Instrumentation found = instrumentation;
        List<Object> objects = new ArrayList<>();
        if (found == null) {
            LOGGER.warn("[Hotwire] {}'s objects cannot be looked through: no instrumentation yet", holder);
            return objects;
        }

        Predicate<Class<?>> own = type -> holder.equals(GameRegistrations.findModOf(type));
        for (Class<?> owner : found.getAllLoadedClasses()) {
            if (owner.isArray() || owner.isHidden() || !holder.equals(GameRegistrations.findModOf(owner))) {
                continue;
            }
            for (FieldSlots.Slot field : Fields.getReferenceFields(owner, true)) {
                try {
                    Object value = field.get(null);
                    if (value != null) {
                        objects.add(value);
                        objects.addAll(Fields.findBelow(value, BELOW, own));
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                }
            }
        }
        List<Object> below = new ArrayList<>();
        for (Object one : objects) {
            Collection<?> items = one instanceof Map<?, ?> map ? Wrappers.readValues(map)
                    : one instanceof Collection<?> collection ? collection : List.of();
            items.stream().limit(1000).filter(item -> item != null && own.test(item.getClass())).forEach(below::add);
            below.addAll(Fields.findInCollectionsOf(one, own));
        }
        objects.addAll(below);
        return objects;
    }

    private static int takeOutOfFields(String holder, Class<?> type, Collection<String> mods, List<Object> objects) {
        int out = 0;
        for (Object object : objects) {
            for (FieldSlots.Slot field : Fields.getReferenceFields(object.getClass(), false)) {
                if (!type.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    Object value = field.get(object);
                    Object java = value == null ? null
                            : unwrap(value instanceof Callback callback ? Callback.get(callback.address()) : value);
                    String mod = java == null ? null : findOwnerOf(java.getClass());
                    if (value == null || java == null) {
                        continue;
                    }
                    if (mod == null || !mods.contains(mod)) {
                        LOGGER.info("[Hotwire] {}.{} holds {} ({}'s), not one of {}'s", object.getClass().getName(),
                                field.getName(), java.getClass().getName(), mod, mods);
                        continue;
                    }
                    Object inner = findWrapped(java, type, mod, 2);
                    field.set(object, inner);
                    out++;
                    REMOVED_FROM_FIELDS.computeIfAbsent(mod, any -> new ArrayList<>())
                            .add(new FieldRemoval(field, object, value, inner));
                    LOGGER.info("[Hotwire] {}.{} of {} holds {} again instead of {}'s", object.getClass().getName(),
                            field.getName(), holder, inner == null ? "nothing" : inner.getClass().getName(), mod);
                } catch (ReflectiveOperationException | RuntimeException e) {
                }
            }
        }
        return out;
    }

    @SuppressWarnings("ReferenceEquality")
    public static synchronized void putBack(Collection<String> mods) {
        List<WrapperRemoval> back = new ArrayList<>();
        List<FieldRemoval> fields = new ArrayList<>();
        mods.forEach(mod -> {
            back.addAll(Objects.requireNonNullElse(REMOVED.remove(mod), List.of()));
            fields.addAll(Objects.requireNonNullElse(REMOVED_FROM_FIELDS.remove(mod), List.of()));
        });
        if (back.isEmpty() && fields.isEmpty()) {
            return;
        }
        GameHold.onGameAndWait(() -> {
            long window = findWindow();
            for (WrapperRemoval one : back) {
                Object current = read(one.setter(), window);
                if (current == one.inner()) {
                    set(one.setter(), window, one.mods());
                }
            }
            for (FieldRemoval one : fields) {
                try {
                    if (one.field().get(one.holder()) == one.inner()) {
                        one.field().set(one.holder(), one.mods());
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    LOGGER.info("[Hotwire] {} took no change: {}", one.field().getName(), e.toString());
                }
            }
        });
    }

    private static long findWindow() {
        //? if >=1.21.9 {
        return Minecraft.getInstance().getWindow().handle();
        //?} else {
        /*return Minecraft.getInstance().getWindow().getWindow();
        *///?}
    }

    private static List<Method> findSetters() {
        List<Method> setters = new ArrayList<>();
        Class<?> glfw;
        try {
            glfw = Class.forName("org.lwjgl.glfw.GLFW", false, GlfwCallbacks.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return List.of();
        }
        for (Method method : glfw.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers()) && method.getName().startsWith("glfwSet")
                    && method.getName().endsWith("Callback") && parameters.length == 2
                    && parameters[0] == long.class && CallbackI.class.isAssignableFrom(parameters[1])) {
                setters.add(method);
            }
        }
        return List.copyOf(setters);
    }

    private static @Nullable Object read(Method setter, long window) {
        try {
            Object previous = setter.invoke(null, window, null);
            setter.invoke(null, window, previous);
            Object set = previous instanceof Callback callback ? Callback.get(callback.address()) : previous;
            return unwrap(set);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static @Nullable Object unwrap(@Nullable Object callback) {
        if (callback == null || !callback.getClass().getName().endsWith("$Container")) {
            return callback;
        }
        for (FieldSlots.Slot field : Fields.getDeclared(callback.getClass())) {
            try {
                if (!field.isStatic() && CallbackI.class.isAssignableFrom(field.getType())) {
                    return field.get(callback);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                return callback;
            }
        }
        return callback;
    }

    private static boolean set(Method setter, long window, @Nullable Object callback) {
        try {
            setter.invoke(null, window, callback);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.info("[Hotwire] {} took no change: {}", setter.getName(), e.toString());
            return false;
        }
    }

    private static @Nullable Object findWrapped(Object mods, Class<?> type, String mod, int depth) {
        for (Class<?> at = mods.getClass(); at != null && at != Object.class; at = at.getSuperclass()) {
            for (FieldSlots.Slot field : Fields.getDeclared(at)) {
                if (field.isStatic() || field.getType().isPrimitive()) {
                    continue;
                }
                try {
                    Object value = field.get(mods);
                    Object java = unwrap(value instanceof Callback callback ? Callback.get(callback.address()) : value);
                    if (java == null) {
                        continue;
                    }
                    boolean theirs = mod.equals(findOwnerOf(java.getClass()));
                    if (type.isInstance(java) && !theirs) {
                        return java;
                    }
                    Object deeper = theirs && depth > 1 ? findWrapped(java, type, mod, depth - 1) : null;
                    if (deeper != null) {
                        return deeper;
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
