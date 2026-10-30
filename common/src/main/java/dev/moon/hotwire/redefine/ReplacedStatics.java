package dev.moon.hotwire.redefine;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ReplacedStatics {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReplacedStatics.class);
    private static volatile Consumer<IdentityHashMap<Object, Object>> swap = replaced -> { };

    private record Change(Field field, Object old, Object made, Map<Field, Object> state,
            InitHookReplay.StaticWriter writer, Set<String> mods) {
    }

    private static final List<Change> CHANGES = new ArrayList<>();
    private static Set<String> goingOff = Set.of();

    private ReplacedStatics() {
    }

    public static void swapWith(Consumer<IdentityHashMap<Object, Object>> by) {
        swap = by;
    }

    public static synchronized void beginSwitch(Collection<String> switched, Collection<String> onAfter) {
        Set<String> off = new HashSet<>();
        for (String id : switched) {
            if (!onAfter.contains(id)) {
                off.add(id);
            }
        }
        goingOff = Set.copyOf(off);
    }

    static synchronized void noteWritten(Field field, Object old, Object made, InitHookReplay.StaticWriter writer) {
        note(new Change(field, old, made, Map.of(), writer, goingOff));
    }

    static synchronized void noteInPlace(Field field, Object holder, Map<Field, Object> before) {
        Map<Field, Object> fields = Collections.unmodifiableMap(new LinkedHashMap<>(before));
        note(new Change(field, holder, holder, fields, (one, value) -> { }, goingOff));
    }

    private static void note(Change change) {
        if (change.mods().isEmpty()) {
            return;
        }
        for (Change one : CHANGES) {
            if (one.field().equals(change.field())) {
                return;
            }
        }
        CHANGES.add(change);
    }

    @SuppressWarnings("ReferenceEquality")
    public static synchronized void putBackFor(Collection<String> onAfter) {
        IdentityHashMap<Object, Object> back = new IdentityHashMap<>();
        CHANGES.removeIf(change -> {
            if (!onAfter.containsAll(change.mods())) {
                return false;
            }
            try {
                Object now = change.field().get(null);
                if (now != change.made()) {
                    LOGGER.info("[Hotwire] {}.{} changed since {} went off; it stays as it is",
                            change.field().getDeclaringClass().getName(), change.field().getName(), change.mods());
                } else if (!change.state().isEmpty()) {
                    for (Map.Entry<Field, Object> part : change.state().entrySet()) {
                        FinalWrites.write(part.getKey(), change.old(), part.getValue());
                    }
                } else {
                    change.writer().write(change.field(), change.old());
                    back.put(change.made(), change.old());
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.warn("[Hotwire] {}.{} could not go back to what it held before {} went off: {}",
                        change.field().getDeclaringClass().getName(), change.field().getName(), change.mods(),
                        e.toString());
            }
            return true;
        });
        swap(back);
    }

    static void swap(IdentityHashMap<Object, Object> replaced) {
        if (!replaced.isEmpty()) {
            swap.accept(replaced);
        }
    }
}
