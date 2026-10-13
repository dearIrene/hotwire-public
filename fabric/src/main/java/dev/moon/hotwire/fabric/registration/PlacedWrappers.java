package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.util.ArrayList;
import java.util.List;

final class PlacedWrappers {
    record Wrapper(FieldSlots.Slot field, Object holder, Object value, String mod) {
    }

    private PlacedWrappers() {
    }

    static List<Wrapper> find(List<Object> objects) {
        List<Wrapper> out = new ArrayList<>();
        for (Object object : objects) {
            for (FieldSlots.Slot field : Fields.getReferenceFields(object.getClass(), false)) {
                if (!field.getType().isInterface()) {
                    continue;
                }
                try {
                    Object value = field.get(object);
                    String mod = value == null ? null : GameRegistrations.findModOf(value.getClass());
                    if (value != null && mod != null) {
                        out.add(new Wrapper(field, object, value, mod));
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                }
            }
        }
        return out;
    }
}
