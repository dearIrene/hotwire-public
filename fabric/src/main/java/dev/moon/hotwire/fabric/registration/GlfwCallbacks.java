package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.version.Spellings;
import dev.moon.hotwire.jvm.JvmAccess;
import dev.moon.hotwire.jvm.LoadedClassList;
import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.redefine.HandlerGuards;
import dev.moon.hotwire.registration.Fields;
import dev.moon.hotwire.registration.GameRegistrations;
import dev.moon.hotwire.registration.Owners;
import dev.moon.hotwire.registration.RegistrationJournal;
import dev.moon.hotwire.registration.Wrappers;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GlfwCallbacks {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlfwCallbacks.class);
    private static final List<Method> SETTERS = findSetters();

    private record WrapperRemoval(Method setter, Object mods, Object inner) implements RegistrationJournal.Take {
        @Override
        @SuppressWarnings("ReferenceEquality")
        public void restore() {
            long window = findWindow();
            if (read(this.setter, window) == this.inner) {
                set(this.setter, window, this.mods);
            }
        }

        @Override
        public String describe() {
            return "the window's " + this.setter.getName();
        }

        @Override
        public RegistrationJournal.Where getWhere() {
            return RegistrationJournal.Where.GAME;
        }
    }

    private record FieldRemoval(FieldSlots.Slot field, Object holder, Object mods, @Nullable Object inner)
            implements RegistrationJournal.Take {
        @Override
        @SuppressWarnings("ReferenceEquality")
        public void restore() {
            try {
                if (this.field.get(this.holder) == this.inner) {
                    this.field.set(this.holder, this.mods);
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(this.field.getName(), e);
            }
        }

        @Override
        public String describe() {
            return this.holder.getClass().getName() + "." + this.field.getName();
        }

        @Override
        public RegistrationJournal.Where getWhere() {
            return RegistrationJournal.Where.GAME;
        }
    }
    private static final int BELOW = 3;

    private static final int WRAP_DEPTH = 4;

    private GlfwCallbacks() {
    }

    private static @Nullable String findOwner(Class<?> type) {
        String merged = Owners.findMergedLambdaMod(type);
        return merged != null ? merged : Owners.findModOrShipper(type);
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

                        if (out > 0) {
                            LOGGER.info("[Hotwire] {} is set through {}'s handler: {} objects of its looked through,"
                                    + " {} of {}'s callbacks taken out", setter.getName(), holder, objects.size(), out,
                                    mods);
                        } else {
                            LOGGER.debug("[Hotwire] {} is set through {}'s handler, none of {}'s callbacks in it",
                                    setter.getName(), holder, mods);
                        }
                    }
                    continue;
                }
                Object current = read(setter, window);
                String mod = current == null ? null : findOwner(current.getClass());
                if (current == null || mod == null || !mods.contains(mod)) {
                    LOGGER.debug("[Hotwire] {} holds {} ({})", setter.getName(), current, mod);
                    if (current != null) {
                        fixBelow(current, setter.getParameterTypes()[1], mods);
                    }
                    continue;
                }
                Object inner = findWrapped(current, setter.getParameterTypes()[1], mods, WRAP_DEPTH);

                if (inner != null && set(setter, window, inner)) {
                    GameRegistrations.getJournal().addTake(mod, new WrapperRemoval(setter, current, inner));
                    LOGGER.info("[Hotwire] the window's {} is {} again instead of {}'s", setter.getName()
                            .replace("glfwSet", "").replace("Callback", " callback"),
                            inner.getClass().getName(), mod);
                    fixBelow(inner, setter.getParameterTypes()[1], mods);
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
        Instrumentation found = JvmAccess.findCurrent();
        List<Object> objects = new ArrayList<>();
        if (found == null) {
            LOGGER.warn("[Hotwire] {}'s objects cannot be looked through: no instrumentation yet", holder);
            return objects;
        }

        Predicate<Class<?>> own = type -> holder.equals(Owners.findModOf(type));
        for (Class<?> owner : LoadedClassList.of(found)) {
            if (owner.isArray() || owner.isHidden() || !holder.equals(Owners.findModOf(owner))) {
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
                    : one instanceof Collection<?> collection ? Wrappers.readItems(collection) : List.of();
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
                    Object java = unwrap(value);
                    String mod = java == null ? null : findOwner(java.getClass());
                    if (value == null || java == null) {
                        continue;
                    }
                    if (mod == null || !mods.contains(mod)) {
                        LOGGER.debug("[Hotwire] {}.{} holds {} ({}'s), not one of {}'s", object.getClass().getName(),
                                field.getName(), java.getClass().getName(), mod, mods);
                        out += fixBelow(java, type, mods);
                        continue;
                    }
                    Object inner = findWrapped(java, type, mods, WRAP_DEPTH);

                    if (inner == null) {
                        LOGGER.info("[Hotwire] {}.{} of {} keeps {}'s callback: what it wraps was not found, and"
                                + " a callback is never put back as nothing", object.getClass().getName(),
                                field.getName(), holder, mod);
                        continue;
                    }
                    field.set(object, inner);
                    out++;
                    GameRegistrations.getJournal().addTake(mod, new FieldRemoval(field, object, value, inner));
                    LOGGER.info("[Hotwire] {}.{} of {} holds {} again instead of {}'s", object.getClass().getName(),
                            field.getName(), holder, inner == null ? "nothing" : inner.getClass().getName(), mod);
                    out += fixBelow(inner, type, mods);
                } catch (ReflectiveOperationException | RuntimeException e) {
                }
            }
        }
        return out;
    }

    private static int fixBelow(Object start, Class<?> type, Collection<String> off) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        return fixBelow(start, type, off, WRAP_DEPTH, seen);
    }

    @SuppressWarnings("ReferenceEquality")
    private static int fixBelow(Object object, Class<?> type, Collection<String> off, int depth, Set<Object> seen) {
        String own = findOwner(object.getClass());
        if (depth <= 0 || own == null || off.contains(own) || "minecraft".equals(own) || !seen.add(object)) {
            return 0;
        }
        int out = 0;
        for (Class<?> at = object.getClass(); at != null && at != Object.class; at = at.getSuperclass()) {
            for (FieldSlots.Slot field : Fields.getDeclared(at)) {
                if (field.isStatic() || field.getType().isPrimitive()) {
                    continue;
                }
                try {
                    Object value = field.get(object);
                    Object java = unwrap(value);
                    if (java == null) {
                        continue;
                    }
                    String mod = findOwner(java.getClass());
                    if (type.isInstance(java)) {
                        LOGGER.debug("[Hotwire] {}.{} passes on to {} ({})", at.getName(), field.getName(),
                                java.getClass().getName(), mod);
                    }
                    if (!type.isInstance(java) || mod == null || !off.contains(mod)) {
                        out += fixBelow(java, type, off, depth - 1, seen);
                        continue;
                    }
                    Object inner = findWrapped(java, type, off, WRAP_DEPTH);
                    if (inner == null) {
                        LOGGER.info("[Hotwire] {}.{} keeps {}'s callback: what it wraps was not found, and a callback"
                                + " is never put back as nothing", at.getName(), field.getName(), mod);
                        continue;
                    }
                    FieldSlots.Slot place = field;
                    Object holder = object;
                    if (value != null && value != java) {
                        place = findDelegate(value.getClass());
                        holder = value;
                    }
                    if (place == null || !place.getType().isInstance(inner)) {
                        continue;
                    }
                    place.set(holder, inner);
                    GameRegistrations.getJournal().addTake(mod, new FieldRemoval(place, holder, java, inner));
                    out++;
                    LOGGER.info("[Hotwire] {}.{} passes on to {} again instead of {}'s", at.getName(), field.getName(),
                            inner.getClass().getName(), mod);
                    out += fixBelow(inner, type, off, depth - 1, seen);
                } catch (ReflectiveOperationException | RuntimeException e) {
                }
            }
        }
        return out;
    }

    private static boolean isOff(Object object, Collection<String> off) {
        String mod = findOwner(object.getClass());
        return mod != null && off.contains(mod);
    }

    private static FieldSlots.@Nullable Slot findDelegate(Class<?> container) {
        for (FieldSlots.Slot field : Fields.getDeclared(container)) {
            if (!field.isStatic() && CallbackI.class.isAssignableFrom(field.getType())) {
                return field;
            }
        }
        return null;
    }

    private static long findWindow() {
        return Spellings.getWindowHandle();
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

    private static @Nullable Object findWrapped(Object wrapper, Class<?> type, Collection<String> off, int depth) {
        for (Class<?> at = wrapper.getClass(); at != null && at != Object.class; at = at.getSuperclass()) {
            for (FieldSlots.Slot field : Fields.getDeclared(at)) {
                if (field.isStatic() || field.getType().isPrimitive()) {
                    continue;
                }
                try {
                    Object value = field.get(wrapper);
                    Object java = unwrap(value);
                    if (java == null) {
                        continue;
                    }
                    boolean theirs = isOff(java, off);
                    if (type.isInstance(java) && !theirs) {
                        return java;
                    }
                    Object deeper = theirs && depth > 1 ? findWrapped(java, type, off, depth - 1) : null;
                    if (deeper != null) {
                        return deeper;
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return null;
                }
            }
        }
        return findWrappedInStatics(wrapper.getClass(), type, off);
    }

    private static @Nullable Object findWrappedInStatics(Class<?> callback, Class<?> type, Collection<String> off) {
        int lambda = callback.isHidden() ? callback.getName().indexOf("$$Lambda") : -1;
        if (lambda < 0) {
            return null;
        }
        try {
            Class<?> host = Class.forName(callback.getName().substring(0, lambda), false, callback.getClassLoader());
            for (FieldSlots.Slot field : Fields.getDeclared(host)) {
                if (!field.isStatic() || field.getType().isPrimitive()) {
                    continue;
                }
                Object value = field.get(null);
                Object java = unwrap(value);
                if (type.isInstance(java) && !isOff(java, off)) {
                    return java;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
        }
        return null;
    }
}
