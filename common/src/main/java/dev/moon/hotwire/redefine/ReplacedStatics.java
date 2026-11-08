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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ReplacedStatics {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReplacedStatics.class);

    private static final int SETTING_UP = 0;
    private static final int SET_UP = 1;
    private static final int GAVE_UP = 2;
    private static volatile Consumer<IdentityHashMap<Object, Object>> swap = replaced -> { };

    private record Change(Field field, Object old, Object made, Map<Field, Object> state,
            IdentityHashMap<Object, Object> parts, InitHookReplay.StaticWriter writer, Set<String> mods) {
    }

    public record StaticWrite(Field field, Object old, Object made) {
    }

    private record Undo(Set<String> mods, List<Runnable> steps) {
    }

    private static final List<Change> CHANGES = new ArrayList<>();
    private static final List<StaticWrite> UNFOLLOWED = new ArrayList<>();
    private static final List<Undo> UNDOS = new ArrayList<>();

    private static @Nullable IdentityHashMap<Object, Object> batch;
    private static Set<String> goingOff = Set.of();
    private static Set<String> onAfterSwitch = Set.of();

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
        onAfterSwitch = Set.copyOf(onAfter);
    }

    static synchronized boolean isOnAfterSwitch(String mod) {
        return !goingOff.contains(mod) && (onAfterSwitch.contains(mod) || !Tripwire.isWatched(mod));
    }

    static synchronized void noteWritten(Field field, Object old, Object made, InitHookReplay.StaticWriter writer) {
        note(new Change(field, old, made, Map.of(), new IdentityHashMap<>(), writer, goingOff));
        if (!goingOff.isEmpty()) {
            UNFOLLOWED.add(new StaticWrite(field, old, made));
        }
    }

    public static synchronized List<StaticWrite> takeWritten() {
        List<StaticWrite> written = List.copyOf(UNFOLLOWED);
        UNFOLLOWED.clear();
        return written;
    }

    public static synchronized void noteUndo(Set<String> mods, List<Runnable> steps) {
        UNDOS.add(new Undo(Set.copyOf(mods), List.copyOf(steps)));
    }

    static synchronized void noteInPlace(Field field, Object holder, Map<Field, Object> before,
            IdentityHashMap<Object, Object> parts) {
        Map<Field, Object> fields = Collections.unmodifiableMap(new LinkedHashMap<>(before));
        note(new Change(field, holder, holder, fields, new IdentityHashMap<>(parts), (one, value) -> { }, goingOff));
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
        UNDOS.removeIf(undo -> {
            if (!onAfter.containsAll(undo.mods())) {
                return false;
            }
            undo.steps().forEach(Runnable::run);
            return true;
        });
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

                    change.parts().forEach((was, part) -> back.put(part, was));
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
        IdentityHashMap<Object, Object> pending = batch;
        if (pending != null) {
            pending.putAll(replaced);
        } else if (!replaced.isEmpty()) {
            swap.accept(replaced);
        }
    }

    public static void runInOneWalk(Consumer<Runnable> onGameThread, Runnable remaking) {
        IdentityHashMap<Object, Object> pending = new IdentityHashMap<>();

        AtomicInteger turn = new AtomicInteger(SETTING_UP);
        onGameThread.accept(() -> {
            batch = pending;
            try {
                remaking.run();
            } finally {
                batch = null;
                if (!turn.compareAndSet(SETTING_UP, SET_UP)) {
                    swap(pending);
                }
            }
        });
        if (!turn.compareAndSet(SETTING_UP, GAVE_UP)) {
            try {
                swap(pending);
            } catch (RuntimeException | LinkageError e) {
                LOGGER.error("[Hotwire] the copies of the statics made again could not all take the new ones", e);
            }
        }
    }
}
