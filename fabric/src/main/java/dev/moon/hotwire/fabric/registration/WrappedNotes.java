package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class WrappedNotes {
    private static final Logger LOGGER = LoggerFactory.getLogger(WrappedNotes.class);
    private final CollectionReferences owner;

    WrappedNotes(CollectionReferences owner) {
        this.owner = owner;
    }

    void noteWrappedInside(Object holder, int steps) {
        CollectionNotes notes = ElementLook.isLooking() ? this.owner.look.getNotes() : null;
        if (notes != null) {
            notes.begin(holder, CollectionNotes.Origin.of(this.owner.readingStaticsOf));
            notes.markWrapped(holder, steps);
        }
        try {
            this.noteWrappedFields(holder, steps);
        } finally {
            if (notes != null) {
                notes.end(holder, true);
            }
        }
    }

    @SuppressWarnings("ReferenceEquality")
    void noteWrappedFields(Object holder, int steps) {
        for (FieldSlots.Slot field : Fields.getReferenceFields(holder.getClass(), false)) {
            Object value;
            try {
                value = field.get(holder);
            } catch (ReflectiveOperationException | RuntimeException e) {
                continue;
            }
            if (value == null || ValueOwners.isPlain(value) || value instanceof Collection<?>
                    || value instanceof Map<?, ?>) {
                continue;
            }
            String mod = GameRegistrations.findModOrShipper(value.getClass());
            if (mod == null && steps > 1 && !Wrappers.isOfAnotherMod(value.getClass()) && this.owner.seen.add(value)) {
                this.noteWrappedInside(value, steps - 1);
                continue;
            }
            if (mod == null || !this.owner.mods.contains(mod)) {
                continue;
            }
            Object inner = ElementTakes.findWrappedValue(value, mod);
            if (inner == null || !field.getType().isInstance(inner)) {
                continue;
            }
            Object mods = value;
            String owner = mod;
            LOGGER.debug("[Hotwire] {}.{} holds {}'s {} around {}; the field takes the wrapped one at the off",
                    holder.getClass().getName(), field.getName(), mod, value.getClass().getName(), inner.getClass());
            WeakReference<Object> weak = new WeakReference<>(holder);
            this.owner.takeOrNote(owner, this.owner.candidates.withOwner(Set.of(), owner), () -> {
                try {
                    Object now = weak.get();
                    return now != null && field.get(now) == mods ? owner : null;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return null;
                }
            }, ElementTakes.makeWeak(holder, (now, taker) -> {
                try {
                    field.set(now, inner);
                    this.owner.getRemovalsOf(taker).add(new Removal.InField(now, field, mods, inner));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    LOGGER.info("[Hotwire] {}.{} keeps {}'s object: {}", now.getClass().getName(),
                            field.getName(), taker, e.toString());
                }
            }), mods);
        }
    }

    void followLambda(Object item) {
        Class<?> type = item.getClass();
        if (!type.isHidden() || !type.getName().contains("$$Lambda")) {
            return;
        }
        for (FieldSlots.Slot field : Fields.getReferenceFields(type, false)) {
            Object captured = CollectionParts.read(field, item);
            if (captured != null && captured.getClass().getName().startsWith("net.minecraft.")
                    && this.owner.seen.add(captured)) {
                this.owner.lookInto(captured);
            }
        }
    }
}
