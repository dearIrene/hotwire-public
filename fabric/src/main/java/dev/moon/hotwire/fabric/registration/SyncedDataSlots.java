package dev.moon.hotwire.fabric.registration;

import java.util.Objects;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import org.jspecify.annotations.Nullable;

public final class SyncedDataSlots {
    private static final ClassValue<Slots> FIRST = new ClassValue<>() {
        @Override
        protected Slots computeValue(Class<?> type) {
            return new Slots();
        }
    };

    private SyncedDataSlots() {
    }

    private static final class Slots {
        volatile SynchedEntityData.DataItem<?> @Nullable [] full;
    }

    public static void fill(Class<?> type, SynchedEntityData.@Nullable DataItem<?>[] items) {
        int empty = -1;
        for (int at = 0; at < items.length; at++) {
            if (items[at] == null) {
                empty = at;
                break;
            }
        }
        Slots slots = FIRST.get(type);
        SynchedEntityData.DataItem<?>[] full = slots.full;
        if (empty < 0) {
            if (full == null) {
                SynchedEntityData.DataItem<?>[] first = new SynchedEntityData.DataItem<?>[items.length];
                for (int at = 0; at < items.length; at++) {
                    first[at] = copy(Objects.requireNonNull(items[at]));
                }
                slots.full = first;
            }
            return;
        }
        if (full == null || full.length != items.length) {
            return;
        }
        for (int at = empty; at < items.length; at++) {
            if (items[at] == null && full[at] != null) {
                items[at] = copy(full[at]);
            }
        }
    }

    private static <T> SynchedEntityData.DataItem<T> copy(SynchedEntityData.DataItem<T> first) {
        EntityDataAccessor<T> accessor = first.getAccessor();
        return new SynchedEntityData.DataItem<>(accessor, accessor.serializer().copy(first.getValue()));
    }
}
