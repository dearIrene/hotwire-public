package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

final class CollectionParts {
    private static final Object FIXED_PROBE = new Object();

    private CollectionParts() {
    }

    static boolean isFixedSize(List<?> list) {
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
            List<Method> accessors = Fields.getRecordParts(item.getClass());
            Class<?>[] types = new Class<?>[accessors.size()];
            Object[] parts = new Object[accessors.size()];
            for (int index = 0; index < accessors.size(); index++) {
                types[index] = accessors.get(index).getReturnType();
                Object part = accessors.get(index).invoke(item);
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
}
