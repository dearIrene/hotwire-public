package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private static final Map<String, List<WrapperRemoval>> REMOVED = new HashMap<>();

    private GlfwCallbacks() {
    }

    public static synchronized void takeOut(Collection<String> mods) {
        GameHold.onGameAndWait(() -> {
            long window = findWindow();
            for (Method setter : SETTERS) {
                Object current = read(setter, window);
                if (current == null) {
                    continue;
                }
                String mod = GameRegistrations.findModOf(current.getClass());
                if (mod == null || !mods.contains(mod)) {
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

    @SuppressWarnings("ReferenceEquality")
    public static synchronized void putBack(Collection<String> mods) {
        List<WrapperRemoval> back = new ArrayList<>();
        mods.forEach(mod -> {
            List<WrapperRemoval> theirs = REMOVED.remove(mod);
            if (theirs != null) {
                back.addAll(theirs);
            }
        });
        if (back.isEmpty()) {
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
                    boolean theirs = mod.equals(GameRegistrations.findModOf(java.getClass()));
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
