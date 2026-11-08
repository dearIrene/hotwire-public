package dev.moon.hotwire.fabric.registration;

import com.google.common.collect.ImmutableCollection;
import dev.moon.hotwire.classfile.PlainTypes;
import dev.moon.hotwire.redefine.FieldSlots;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class CollectionParts {
    private static final Logger LOGGER = LoggerFactory.getLogger(CollectionParts.class);

    private static final Set<String> UNREADABLE = ConcurrentHashMap.newKeySet();

    private static final int CHANGED_READS = 20;

    private static final Object FIXED_PROBE = new Object();

    private CollectionParts() {
    }

    private static final String JDK_IMMUTABLE = List.of().getClass().getNestHost().getName() + "$";
    private static final String JDK_UNMODIFIABLE = Collections.class.getName() + "$Unmodifiable";
    private static final ClassValue<Boolean> REFUSES = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            String name = type.getName();
            return name.startsWith(JDK_IMMUTABLE) || name.startsWith(JDK_UNMODIFIABLE)
                    || ImmutableCollection.class.isAssignableFrom(type);
        }
    };

    private static final ClassValue<Boolean> PRIMITIVE_ONLY = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return PlainTypes.isPrimitiveCollection(type.getName());
        }
    };

    static boolean holdsPrimitivesOnly(Object container) {
        return PRIMITIVE_ONLY.get(container.getClass());
    }

    static boolean isFixedSize(List<?> list) {
        if (REFUSES.get(list.getClass())) {
            return true;
        }
        try {
            list.remove(FIXED_PROBE);
            return false;
        } catch (UnsupportedOperationException e) {
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static @Nullable String findNamespaceOf(@Nullable Object key) {
        return key instanceof Identifier id ? id.getNamespace() : null;
    }

    static @Nullable Object withStandIns(String mod, Object item) {
        try {
            List<FieldSlots.Slot> fields = Fields.getRecordParts(item.getClass());
            Class<?>[] types = new Class<?>[fields.size()];
            @Nullable Object[] parts = new @Nullable Object[fields.size()];
            for (int index = 0; index < fields.size(); index++) {
                types[index] = fields.get(index).getType();
                Object part = fields.get(index).get(item);
                if (part != null && !ValueOwners.isPlain(part)
                        && (ValueOwners.isOwnedBy(mod, part) || ValueOwners.forwardsTo(mod, part))) {
                    if (!StandIns.fits(types[index])) {
                        return null;
                    }
                    part = StandIns.of(types[index]);
                }
                parts[index] = part;
            }
            Constructor<?> canonical = item.getClass().getDeclaredConstructor(types);
            canonical.setAccessible(true);
            return canonical.newInstance(parts);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    static @Nullable Object read(FieldSlots.Slot field, @Nullable Object holder) {
        try {
            return field.get(holder);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    static void readChanging(Object value, Consumer<Object> read, ElementLook look, List<String> mods) {
        for (int attempt = 1; ; attempt++) {
            try {
                read.accept(value);
                return;
            } catch (LinkageError e) {
                if (UNREADABLE.add(value.getClass().getName())) {
                    LOGGER.info("[Hotwire] {} cannot be read ({}); the removal passes it by",
                            value.getClass().getName(), e.toString());
                }
                return;
            } catch (ConcurrentModificationException | IndexOutOfBoundsException | NoSuchElementException e) {
                WeakReference<Object> weak = new WeakReference<>(value);
                if (look.hasNoted(() -> {
                    Object now = weak.get();
                    if (now != null) {
                        readChanging(now, read, look, mods);
                    }
                })) {
                    return;
                }
                if (attempt == CHANGED_READS) {
                    LOGGER.warn("[Hotwire] {} changed under the removal {} times; what it holds of {} stays",
                            value.getClass().getName(), CHANGED_READS, mods);
                    return;
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    static boolean isGameObject(Object value) {
        String name = value.getClass().getName();
        return !(value instanceof Collection<?>) && !(value instanceof Map<?, ?>) && !(value instanceof Object[])
                && (name.startsWith("net.minecraft.") || name.startsWith("com.mojang."));
    }
}
