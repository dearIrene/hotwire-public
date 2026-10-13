package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

final class CallbackCheck {
    private CallbackCheck() {
    }

    static boolean callsInto(Object value, String mod, Function<Class<?>, @Nullable String> lambdaMod) {
        if (isGame(value.getClass())) {
            return false;
        }
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Object> step = List.of(value);
        for (int depth = 0; depth < 3 && !step.isEmpty() && seen.size() < 64; depth++) {
            List<Object> next = new ArrayList<>();
            for (Object one : step) {
                Class<?> type = one.getClass();
                if (!seen.add(one)) {
                    continue;
                }
                if (depth > 0 && (mod.equals(GameRegistrations.findModOf(type)) || mod.equals(lambdaMod.apply(type)))) {
                    return true;
                }
                if (isGame(type) || type.isArray() || one instanceof Collection<?> || one instanceof Map<?, ?>
                        || type.getName().startsWith("java.lang.")) {
                    continue;
                }
                for (FieldSlots.Slot field : Fields.getReferenceFields(type, false)) {
                    try {
                        Object fieldValue = field.get(one);
                        if (fieldValue != null && !(fieldValue instanceof String) && !(fieldValue instanceof Number)) {
                            next.add(fieldValue);
                        }
                    } catch (IllegalAccessException e) {
                    }
                }
            }
            step = next;
        }
        return false;
    }

    private static boolean isGame(Class<?> type) {
        return type.getName().startsWith("net.minecraft.") || type.getName().startsWith("com.mojang.");
    }
}
