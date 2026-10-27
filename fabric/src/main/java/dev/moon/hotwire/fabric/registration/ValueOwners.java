package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.jspecify.annotations.Nullable;

final class ValueOwners {
    private ValueOwners() {
    }

    private static volatile Function<Class<?>, @Nullable String> lambdaOwner = type -> null;

    static void setLambdaOwners(Function<Class<?>, @Nullable String> owner) {
        lambdaOwner = owner;
    }

    static @Nullable String findLambdaOwner(Class<?> type) {
        return lambdaOwner.apply(type);
    }

    static boolean isLambda(Object item) {
        Class<?> type = item.getClass();
        return type.isHidden() && type.getName().contains("$$Lambda");
    }

    static @Nullable String findForwardedTo(Object item) {
        Class<?> type = item.getClass();
        if (!type.isHidden() || !type.getName().contains("$$Lambda")) {
            return null;
        }
        String owner = null;
        try {
            for (FieldSlots.Slot field : Fields.getReferenceFields(type, false)) {
                Object part = field.get(item);
                if (part == null || isPlain(part) || isGame(part)) {
                    continue;
                }
                String of = findOwnerOfValue(part);
                if (of == null || (owner != null && !owner.equals(of))) {
                    return null;
                }
                owner = of;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
        return owner;
    }

    static @Nullable String findOwnerOfValue(Object value) {
        String id = findIdOwner(value);
        if (id != null) {
            return id;
        }
        Class<?> type = value instanceof Class<?> named ? named : value.getClass();
        String mod = GameRegistrations.findModOf(type);
        if (mod == null && type.isHidden()) {
            mod = lambdaOwner.apply(type);
        }
        if (mod != null || value instanceof Class<?> || !type.isHidden()) {
            return mod;
        }
        for (FieldSlots.Slot field : Fields.getDeclared(type)) {
            if (field.isStatic() || field.getType().isPrimitive()) {
                continue;
            }
            try {
                Object captured = field.get(value);
                @SuppressWarnings("ReferenceEquality")
                boolean itself = captured == value;
                String of = captured == null || itself ? null : GameRegistrations.findModOf(captured.getClass());
                if (of == null && captured != null && captured.getClass().isHidden()) {
                    of = lambdaOwner.apply(captured.getClass());
                }
                if (of != null && (mod == null || mod.equals(of))) {
                    mod = of;
                } else if (of != null) {
                    return null;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
            }
        }
        return mod;
    }

    static boolean forwardsTo(String mod, Object item) {
        Class<?> type = item.getClass();

        if (!type.isHidden() || !type.getName().contains("$$Lambda") || isOwnedClass(mod, type)) {
            return false;
        }
        int mods = 0;
        try {
            for (FieldSlots.Slot field : Fields.getReferenceFields(type, false)) {
                Object part = field.get(item);
                if (part == null || isPlain(part) || isGame(part)) {
                    continue;
                }
                if (!isOwnedBy(mod, part)) {
                    return false;
                }
                mods++;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
        return mods > 0;
    }

    private static boolean isGame(Object part) {
        String name = part.getClass().getName();
        return (name.startsWith("net.minecraft.") || name.startsWith("com.mojang.")) && !part.getClass().isHidden();
    }

    static @Nullable String findIdOwner(Object value) {
        Identifier id = value instanceof Identifier plain ? plain
                : value instanceof ResourceKey<?> key ? findIdOf(key) : null;
        String space = id == null ? null : id.getNamespace();
        return space == null || space.equals("minecraft") ? null : space;
    }

    private static Identifier findIdOf(ResourceKey<?> key) {
        //? if >=1.21.11 {
        return key.identifier();
        //?} else {
        /*return key.location();
        *///?}
    }

    static boolean isPlain(Object part) {
        return part instanceof String || part instanceof Number || part instanceof Boolean
                || part instanceof Character || part instanceof Enum<?>;
    }

    static boolean isOwnedBy(String mod, Object value) {
        Class<?> type = value instanceof Class<?> named ? named : value.getClass();
        return isOwnedClass(mod, type) || mod.equals(findIdOwner(value))
                || (!(value instanceof Class<?>) && type.isHidden() && hasOwnedCapture(mod, value));
    }

    private static boolean isOwnedClass(String mod, Class<?> type) {
        return mod.equals(GameRegistrations.findModOf(type))
                || (type.isHidden() && mod.equals(lambdaOwner.apply(type)));
    }

    @SuppressWarnings("ReferenceEquality")
    private static boolean hasOwnedCapture(String mod, Object lambda) {
        for (FieldSlots.Slot field : Fields.getDeclared(lambda.getClass())) {
            if (field.isStatic() || field.getType().isPrimitive()) {
                continue;
            }
            try {
                Object captured = field.get(lambda);
                if (captured != null && captured != lambda && isOwnedClass(mod, captured.getClass())) {
                    return true;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
            }
        }
        return false;
    }
}
