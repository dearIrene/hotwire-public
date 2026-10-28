package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
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

    private record EarlyOwners(Set<String> mods, List<Integer> made, Set<String> owners) {
    }

    private static volatile @Nullable EarlyOwners modelsAhead;

    private ModelReloadCheck() {
    }

    public static void readAhead(Collection<String> mods) {
        List<Integer> made = findMade();
        modelsAhead = new EarlyOwners(Set.copyOf(mods), made, Set.copyOf(findModelOwners(mods)));
    }

    private static List<Integer> findMade() {
        Object manager = Minecraft.getInstance().getModelManager();
        List<Integer> made = new ArrayList<>();
        for (Class<?> at = manager.getClass(); at != null && at != Object.class; at = at.getSuperclass()) {
            for (FieldSlots.Slot field : Fields.getDeclared(at)) {
                int access = field.getModifiers();
                if (!Modifier.isStatic(access) && !Modifier.isFinal(access) && !field.getType().isPrimitive()) {
                    made.add(System.identityHashCode(read(field, manager)));
                }
            }
        }
        return made;
    }

    public static boolean hasModelsOf(Collection<String> mods) {
        EarlyOwners ahead = modelsAhead;
        modelsAhead = null;
        Set<String> found = ahead != null && ahead.mods().equals(Set.copyOf(mods))
                && ahead.made().equals(findMade()) ? ahead.owners() : findModelOwners(mods);
        noteModels(found);
        return !found.isEmpty();
    }

    public static Set<String> findModelOwners(Collection<String> mods) {
        Set<String> found = new HashSet<>();
        Object manager = Minecraft.getInstance().getModelManager();
        Set<String> wanted = Set.copyOf(mods);
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(manager);
        Map<Class<?>, @Nullable String> owners = new HashMap<>();
        for (Class<?> at = manager.getClass(); at != null && at != Object.class; at = at.getSuperclass()) {
            for (FieldSlots.Slot field : Fields.getDeclared(at)) {
                int access = field.getModifiers();
                if (!Modifier.isStatic(access) && !Modifier.isFinal(access) && !field.getType().isPrimitive()) {
                    List<String> path = new ArrayList<>(List.of(at.getSimpleName() + "." + field.getName()));
                    look(read(field, manager), wanted, found, seen, owners, path, 1, DEPTH);
                }
            }
        }
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
        look(fonts, Set.copyOf(mods), found, Collections.newSetFromMap(new IdentityHashMap<>()), new HashMap<>(),
                new ArrayList<>(), 0, FONT_DEPTH);
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
            Map<Class<?>, @Nullable String> owners, List<String> path, int depth, int most) {
        if (value == null || !seen.add(value) || found.containsAll(mods)) {
            return;
        }
        String owner = owners.computeIfAbsent(value.getClass(), GameRegistrations::findModOf);
        if (owner != null && mods.contains(owner)) {
            if (found.add(owner)) {
                LOGGER.info("[Hotwire] the game's models hold {}'s {} at {}", owner, value.getClass().getName(),
                        String.join(" > ", path));
            }
            return;
        }
        if (depth > 0 && isShared(value)) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            path.add(value.getClass().getSimpleName() + " values");
            look(Wrappers.readValues(map), mods, found, seen, owners, path, depth, most);
            path.removeLast();
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
                            LOGGER.info("[Hotwire] the game's models hold {}'s {} at {} > {}", of,
                                    item.getClass().getName(), String.join(" > ", path),
                                    value.getClass().getSimpleName() + "[" + (at - 1) + "]");
                        }
                    } else if (depth < most && !item.getClass().getName().startsWith("java.")) {
                        path.add(value.getClass().getSimpleName() + "[" + (at - 1) + "]");
                        look(item, mods, found, seen, owners, path, depth + 1, most);
                        path.removeLast();
                    }
                }
            } catch (ConcurrentModificationException changing) {
                found.addAll(mods);
            } catch (RuntimeException refused) {
            }
            return;
        }
        if (value.getClass().getName().startsWith("java.")) {
            return;
        }
        for (Class<?> at = value.getClass(); at != null && at != Object.class; at = at.getSuperclass()) {
            for (FieldSlots.Slot field : Fields.getDeclared(at)) {
                int next = isWrapped(field, value) ? depth : depth + 1;
                if (field.isStatic() || field.getType().isPrimitive() || next > most) {
                    continue;
                }
                path.add(at.getSimpleName() + "." + field.getName());
                look(read(field, value), mods, found, seen, owners, path, next, most);
                path.removeLast();
            }
        }
    }

    private static boolean isWrapped(FieldSlots.Slot field, Object holder) {
        Class<?> type = field.getType();
        return type != Object.class && !type.getName().startsWith("java.") && type.isInstance(holder);
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
