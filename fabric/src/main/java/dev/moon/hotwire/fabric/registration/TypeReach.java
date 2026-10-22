package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class TypeReach {
    private static final ClassValue<Boolean> PLATFORM = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            String name = type.getName();
            return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
                    || name.startsWith("net.minecraft.") || name.startsWith("com.mojang.")
                    || ClassLoader.getSystemResource(name.replace('.', '/') + ".class") != null;
        }
    };

    private static volatile Set<Class<?>> extended = Set.of();
    private static volatile int extendedOf = -1;

    private final Set<String> mods;
    private final Set<Class<?>> others;
    private final Map<Type, Boolean> known = new HashMap<>();
    private final Map<Class<?>, Boolean> knownInstances = new HashMap<>();

    TypeReach(Set<String> mods, Collection<Class<?>> loaded) {
        this.mods = mods;
        this.others = findExtended(loaded);
    }

    private static Set<Class<?>> findExtended(Collection<Class<?>> loaded) {
        if (loaded.size() == extendedOf) {
            return extended;
        }
        Set<Class<?>> found = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Class<?>> todo = new ArrayList<>();
        for (Class<?> one : loaded) {
            if (!one.isArray() && !one.isPrimitive() && !PLATFORM.get(one)) {
                todo.add(one);
            }
        }
        while (!todo.isEmpty()) {
            Class<?> one = todo.removeLast();
            if (found.add(one)) {
                Class<?> parent = one.getSuperclass();
                if (parent != null) {
                    todo.add(parent);
                }
                todo.addAll(List.of(one.getInterfaces()));
            }
        }
        extended = found;
        extendedOf = loaded.size();
        return found;
    }

    boolean canHold(Type type) {
        Boolean answer = this.known.get(type);
        if (answer != null) {
            return answer;
        }

        this.known.put(type, true);
        boolean found = switch (type) {
            case Class<?> named -> this.findOf(named);
            case ParameterizedType generic -> this.findOf(generic);
            case GenericArrayType array -> this.canHold(array.getGenericComponentType());
            case WildcardType wildcard -> this.canAnyHold(wildcard.getUpperBounds());
            default -> true;
        };
        this.known.put(type, found);
        return found;
    }

    boolean canInstanceHold(Class<?> type) {
        Boolean answer = this.knownInstances.get(type);
        if (answer != null) {
            return answer;
        }
        this.knownInstances.put(type, true);
        boolean found = type.isArray() ? this.canHold(type.getComponentType())
                : this.isOfTheMods(type) || isContainer(type) || (!isOfJdk(type) && this.canAnyFieldHold(type));
        this.knownInstances.put(type, found);
        return found;
    }

    private boolean findOf(ParameterizedType generic) {
        if (!(generic.getRawType() instanceof Class<?> raw)) {
            return true;
        }
        return isContainer(raw) ? this.canAnyHold(generic.getActualTypeArguments()) : this.canHold(raw);
    }

    private boolean findOf(Class<?> type) {
        if (type.isPrimitive()) {
            return false;
        }
        if (type.isArray()) {
            return this.canHold(type.getComponentType());
        }

        if (type == Class.class || type == Object.class || this.isOfTheMods(type) || isContainer(type)) {
            return true;
        }
        boolean open = type.isInterface() || (!Modifier.isFinal(type.getModifiers()) && !type.isEnum());
        if (open) {
            return !PLATFORM.get(type) || isFunctional(type) || this.isExtendedByOthers(type);
        }
        return !isOfJdk(type) && this.canAnyFieldHold(type);
    }

    private boolean canAnyFieldHold(Class<?> type) {
        for (FieldSlots.Slot field : Fields.getReferenceFields(type, false)) {
            if (this.canHold(Fields.findGenericType(field))) {
                return true;
            }
        }
        return false;
    }

    private boolean canAnyHold(Type[] types) {
        for (Type one : types) {
            if (this.canHold(one)) {
                return true;
            }
        }
        return false;
    }

    private boolean isOfTheMods(Class<?> type) {
        String mod = GameRegistrations.findModOf(type);
        return mod != null && this.mods.contains(mod);
    }

    private boolean isExtendedByOthers(Class<?> type) {
        return this.others.contains(type);
    }

    private static boolean isContainer(Class<?> type) {
        return Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)
                || Iterable.class.isAssignableFrom(type) || type == Optional.class || type == Map.Entry.class;
    }

    private static boolean isOfJdk(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.");
    }

    private static boolean isOfObject(Method method) {
        try {
            Object.class.getMethod(method.getName(), method.getParameterTypes());
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean isFunctional(Class<?> type) {
        if (!type.isInterface()) {
            return false;
        }
        int abstracts = 0;
        try {
            for (Method method : type.getMethods()) {
                if (Modifier.isAbstract(method.getModifiers()) && !isOfObject(method)) {
                    abstracts++;
                }
            }
        } catch (LinkageError e) {
            return true;
        }
        return abstracts == 1;
    }
}
