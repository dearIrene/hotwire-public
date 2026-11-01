package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.redefine.InitialValues;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class FieldGiveBacks {
    private static final Logger LOGGER = LoggerFactory.getLogger(FieldGiveBacks.class);
    private final FieldReferences owner;

    FieldGiveBacks(FieldReferences owner) {
        this.owner = owner;
    }

    void giveBack(Class<?> owner, @Nullable Object holder, String mod, List<FieldReferences.Replacement> mine,
            List<FieldReferences.RemainingReference> retained, boolean looksInto) {
        List<FieldSlots.Slot> fields = Fields.getReferenceFields(owner, holder == null);
        for (FieldSlots.Slot field : fields) {
            if (field.getType().isPrimitive()) {
                continue;
            }
            try {
                Object value = field.get(holder);
                if (value != null && holder != null && mod.equals(this.owner.lambdas.findModOf(value.getClass()))) {
                    Object inner = Lambdas.findWrapped(value, field.getType(), mod);
                    if (inner != null) {
                        mine.add(new FieldReferences.Replacement(field, holder, value, inner));
                    }
                    continue;
                }
                if (value == null || (!mod.equals(GameRegistrations.findModOf(value.getClass()))
                        && !(looksInto && CallbackCheck.callsInto(value, mod, this.owner.lambdas::findModOf)))) {
                    continue;
                }
                Class<?> declaring = field.getDeclaringClass();
                byte[] original = this.owner.originalBytes.apply(declaring.getName().replace('.', '/'));
                String descriptor = field.getType().descriptorString();

                if (original != null && !ClassFileSummary.isDeclaredBy(original, field.getName(), descriptor)) {
                    continue;
                }

                boolean handed = holder == null && looksInto;
                if (original != null && !handed
                        && ClassFileSummary.isSetAgainLater(original, field.getName(), descriptor, holder == null)) {
                    LOGGER.info("[Hotwire] {}.{} keeps {}'s object: its class sets it again over its life",
                            declaring.getName(), field.getName(), mod);
                    continue;
                }
                Optional<Object> own = original == null ? Optional.empty() : holder == null
                        ? InitialValues.ofStatic(declaring, original, field.getName(), descriptor)
                        : InitialValues.ofInstance(declaring, original, holder, field.getName(), descriptor);
                if ((own.isEmpty() || mod.equals(GameRegistrations.findModOf(own.get().getClass())))
                        && StandIns.fits(field.getType())) {
                    own = Optional.of(StandIns.of(field.getType()));
                }
                if (own.isEmpty() || mod.equals(GameRegistrations.findModOf(own.get().getClass()))) {
                    retained.add(new FieldReferences.RemainingReference(field, holder, value));
                    continue;
                }
                mine.add(new FieldReferences.Replacement(field, holder, value, own.get()));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOGGER.warn("[Hotwire] {}.{} could not be looked at for {}'s objects", owner.getName(),
                        field.getName(), mod, e);
            }
        }
    }

    @SuppressWarnings("ReferenceEquality")
    static void checkSameAsGiven(List<FieldReferences.RemainingReference> retained,
            List<FieldReferences.Replacement> mine) {
        for (FieldReferences.RemainingReference one : retained) {
            FieldReferences.Replacement given = mine.stream().filter(removed -> removed.modsValue() == one.value(
                    )).findFirst()
                    .orElse(null);
            if (given == null) {
                LOGGER.info("[Hotwire] {}.{} keeps the mod's object: its class sets nothing plain there",
                        one.field().getDeclaringClass().getName(), one.field().getName());
            } else {
                mine.add(new FieldReferences.Replacement(one.field(), one.holder(), one.value(), given.put()));
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void carry(Object from, Object to) {
        try {
            if (from instanceof Map fromMap && to instanceof Map toMap) {
                toMap.clear();
                toMap.putAll(fromMap);
            } else if (from instanceof Collection fromItems && to instanceof Collection toItems) {
                toItems.clear();
                toItems.addAll(fromItems);
            }
        } catch (UnsupportedOperationException e) {
            LOGGER.info("[Hotwire] a {} takes no change; it goes in as it is", to.getClass().getName());
        }
    }
}
