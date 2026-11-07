package dev.moon.hotwire.fabric;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

final class WorldRendererReset {
    private static final int DEPTH = 3;

    private WorldRendererReset() {
    }

    static boolean holdsAny(List<Class<?>> types) {
        Minecraft game = Minecraft.getInstance();
        List<Class<?>> mods = types.stream().filter(type -> !isGame(type)).toList();
        if (game.level == null || mods.isEmpty()) {
            return false;
        }
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object> level = new ArrayDeque<>(List.of(game.levelRenderer));
        for (int depth = 0; depth <= DEPTH && !level.isEmpty(); depth++) {
            Deque<Object> next = new ArrayDeque<>();
            for (Object object : level) {
                if (!seen.add(object)) {
                    continue;
                }
                for (Class<?> type : mods) {
                    if (type.isInstance(object)) {
                        return true;
                    }
                }
                addFields(object, next);
            }
            level = next;
        }
        return false;
    }

    private static void addFields(Object object, Deque<Object> into) {
        for (Class<?> at = object.getClass(); at != null && !isJdk(at); at = at.getSuperclass()) {
            for (Field field : at.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                Object inField = read(field, object);
                if (inField != null && !inField.getClass().isArray() && !isJdk(inField.getClass())
                        && !(inField instanceof Minecraft)) {
                    into.add(inField);
                }
            }
        }
    }

    private static @Nullable Object read(Field field, Object object) {
        try {
            field.setAccessible(true);
            return field.get(object);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static boolean isGame(Class<?> type) {
        String name = type.getName();
        return name.startsWith("net.minecraft.") || name.startsWith("com.mojang.");
    }

    private static boolean isJdk(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
                || name.startsWith("sun.");
    }

    static void run() {
        Minecraft game = Minecraft.getInstance();
        if (game.level == null) {
            return;
        }
        //? if >=26.2 {
        game.levelExtractor.allChanged();
        //?} else {
        /*game.levelRenderer.allChanged();
        *///?}
    }
}
