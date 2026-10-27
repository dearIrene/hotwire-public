package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModelReloadCheck {
    private static final int DEPTH = 3;

    private static final int FONT_DEPTH = 10;

    private static final int LOOKED_AT = 50_000;

    private static final int ARRAY_LOOKED_AT = 64;
    private static final Logger LOGGER = LoggerFactory.getLogger(ModelReloadCheck.class);
    private static final Set<String> MODEL_OWNERS = ConcurrentHashMap.newKeySet();
    private static final Set<String> FONT_OWNERS = ConcurrentHashMap.newKeySet();

    private ModelReloadCheck() {
    }

    public static boolean hasModelsOf(Collection<String> mods) {
        Set<String> found = findModelOwners(mods);
        noteModels(found);
        return !found.isEmpty();
    }

    public static Set<String> findModelOwners(Collection<String> mods) {
        Set<String> found = new HashSet<>();
        Object manager = Minecraft.getInstance().getModelManager();
        look(manager, Set.copyOf(mods), found, Collections.newSetFromMap(new IdentityHashMap<>()), new HashMap<>(), 0,
                DEPTH);
        return found;
    }

    public static void noteModels(Collection<String> mods) {
        MODEL_OWNERS.addAll(mods);
    }

    public static boolean hasFontsOf(Object fonts, Collection<String> mods) {
        Set<String> found = findFontOwners(fonts, mods);
        noteFonts(found);
        return !found.isEmpty();
    }

    public static Set<String> findFontOwners(Object fonts, Collection<String> mods) {
        Set<String> found = new HashSet<>();
        look(fonts, Set.copyOf(mods), found, Collections.newSetFromMap(new IdentityHashMap<>()), new HashMap<>(), 0,
                FONT_DEPTH);
        return found;
    }

    public static void noteFonts(Collection<String> mods) {
        FONT_OWNERS.addAll(mods);
    }

    public static boolean hasFontsAtOn(Collection<String> mods) {
        boolean any = false;
        for (String mod : mods) {
            any |= FONT_OWNERS.remove(mod);
        }
        return any;
    }

    public static boolean bakeAtOn(Collection<String> mods) {
        boolean any = false;
        for (String mod : mods) {
            any |= MODEL_OWNERS.remove(mod);
        }
        return any;
    }

    private static void look(@Nullable Object value, Set<String> mods, Set<String> found, Set<Object> seen,
            Map<Class<?>, @Nullable String> owners, int depth, int most) {
        if (value == null || !seen.add(value) || found.containsAll(mods)) {
            return;
        }
        String owner = owners.computeIfAbsent(value.getClass(), GameRegistrations::findModOf);
        if (owner != null && mods.contains(owner)) {
            if (found.add(owner)) {
                LOGGER.info("[Hotwire] the game's models hold {}'s {}", owner, value.getClass().getName());
            }
            return;
        }
        if (depth > 0 && isShared(value)) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            look(Wrappers.readValues(map), mods, found, seen, owners, depth, most);
            return;
        }

        if (value instanceof Collection<?> && owner != null) {
            return;
        }
        if (value instanceof Collection<?> || value instanceof Object[]) {
            Iterable<?> items = value instanceof Object[] array ? Arrays.asList(array) : (Collection<?>) value;
            int limit = value instanceof Object[] ? ARRAY_LOOKED_AT : LOOKED_AT;
            int at = 0;
            try {
                for (Object item : items) {
                    if (item == null) {
                        continue;
                    }
                    if (at++ >= limit) {
                        break;
                    }
                    String of = owners.computeIfAbsent(item.getClass(), GameRegistrations::findModOf);
                    if (of != null && mods.contains(of)) {
                        if (found.add(of)) {
                            LOGGER.info("[Hotwire] the game's models hold {}'s {}", of, item.getClass().getName());
                        }
                    } else if (depth < most && !item.getClass().getName().startsWith("java.")) {
                        look(item, mods, found, seen, owners, depth + 1, most);
                    }
                }
            } catch (ConcurrentModificationException changing) {
                found.addAll(mods);
            } catch (RuntimeException refused) {
            }
            return;
        }
        if (depth >= most || value.getClass().getName().startsWith("java.")) {
            return;
        }
        for (Class<?> at = value.getClass(); at != null && at != Object.class; at = at.getSuperclass()) {
            for (FieldSlots.Slot field : Fields.getDeclared(at)) {
                if (field.isStatic() || field.getType().isPrimitive()) {
                    continue;
                }
                look(read(field, value), mods, found, seen, owners, depth + 1, most);
            }
        }
    }

    private static boolean isShared(Object value) {
        return value instanceof PreparableReloadListener || value instanceof ResourceManager
                || value instanceof Minecraft;
    }

    private static @Nullable Object read(FieldSlots.Slot field, Object holder) {
        try {
            return field.get(holder);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }
}
