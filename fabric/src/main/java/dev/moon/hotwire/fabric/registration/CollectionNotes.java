package dev.moon.hotwire.fabric.registration;

import com.google.common.collect.MapMaker;
import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.registration.Fingerprints;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

final class CollectionNotes {
    private static final Object NOWHERE = new Object();

    private record Frame(Object container, Note note, List<Runnable> before, List<Runnable> now, boolean fresh,
            long print) {
    }

    record Origin(@Nullable String staticsOf, boolean game, FieldSlots.@Nullable Slot field) {
        private static final Origin NONE = new Origin(null, false, null);
        private static final Map<String, Origin> PLAIN = new ConcurrentHashMap<>();

        static Origin of(@Nullable String staticsOf) {
            return staticsOf == null ? NONE : PLAIN.computeIfAbsent(staticsOf, mod -> new Origin(mod, false, null));
        }
    }

    private static final class Note extends WeakReference<Object> {
        private List<Runnable> takes = List.of();
        private Origin origin;
        private @Nullable Set<String> under;
        private long print = Fingerprints.UNKNOWN;
        private boolean ofWorld;
        private boolean readAgain;
        private boolean filed = true;

        private int wrapped;

        private Note(Object container, Origin origin, ReferenceQueue<Object> gone) {
            super(container, gone);
            this.origin = origin;
        }

        private @Nullable Object findFiled() {
            return this.filed ? this.get() : null;
        }
    }

    private final Map<Object, Note> notes = new MapMaker().weakKeys().makeMap();

    private final ReferenceQueue<Object> gone = new ReferenceQueue<>();

    private int dead;

    private final List<Note> order = new ArrayList<>();
    private List<Runnable> nowhere = new ArrayList<>();

    private static final String ANY = "";

    private final Set<Class<?>> staticsRead = Collections.newSetFromMap(new MapMaker().weakKeys().makeMap());

    private @Nullable Collection<String> lookingFor;
    private boolean every;

    private List<Object> toRead = List.of();
    private final Deque<Frame> open = new ArrayDeque<>();

    private boolean inWorld;
    private int lastForAnyHold;
    private int leftUnchanged;
    private boolean printing;

    private final Map<String, Set<Object>> restored = new HashMap<>();

    private @Nullable Object printed;
    private long printedAs = Fingerprints.UNKNOWN;

    synchronized void startLook(@Nullable Collection<String> mods, boolean printing) {
        this.expunge();
        this.printed = null;
        this.leftUnchanged = 0;
        this.printing = printing;
        this.lookingFor = mods == null ? null : List.copyOf(mods);
        this.every = mods == null;
        this.dropLook();
        if (this.every) {
            for (Note note : this.order) {
                Object container = note.findFiled();
                if (container != null && note.ofWorld) {
                    this.remove(container, note);
                }
            }
            this.order.removeIf(note -> note.findFiled() == null);
            this.dead = 0;
            this.nowhere = new ArrayList<>();
        }
        this.toRead = this.every ? this.listContainers() : List.of();
    }

    synchronized boolean isForEvery() {
        return this.every;
    }

    synchronized void dropLook() {
        while (!this.open.isEmpty()) {
            Frame frame = this.open.pop();
            if (frame.fresh()) {
                this.remove(frame.container(), frame.note());
            } else {
                frame.note().takes = frame.before();
            }
        }
    }

    synchronized void setInWorld(boolean inWorld) {
        this.inWorld = inWorld;
    }

    @SuppressWarnings("ReferenceEquality")
    synchronized void begin(Object container, Origin origin) {
        this.expunge();
        Frame outer = this.open.peek();
        if (outer != null && outer.container() == container) {
            this.printed = null;
            this.open.push(outer);
            return;
        }
        Note note = this.notes.get(container);
        boolean fresh = note == null;
        if (note == null) {
            note = new Note(container, origin, this.gone);
            this.notes.put(container, note);
        }
        List<Runnable> before = note.takes;
        List<Runnable> left = new ArrayList<>();
        if (this.lookingFor != null) {
            for (Runnable take : before) {
                if (take instanceof ElementTakes.NotedTake filed && filed.candidates() != null
                        && Collections.disjoint(filed.candidates(), this.lookingFor)) {
                    left.add(take);
                }
            }
        }
        note.takes = left;
        note.origin = origin;
        note.ofWorld |= this.inWorld;
        long print = this.printed == container ? this.printedAs : Fingerprints.of(container);
        this.printed = null;
        this.open.push(new Frame(container, note, before, left, fresh, print));
    }

    @SuppressWarnings("ReferenceEquality")
    synchronized void end(Object container, boolean completed) {
        Frame frame = this.open.peek();
        if (frame == null || frame.container() != container) {
            return;
        }
        this.open.pop();
        if (frame == this.open.peek()) {
            return;
        }
        Note note = frame.note();
        if (!completed) {
            note.takes = frame.before();
        } else {
            long now = this.printing ? Fingerprints.of(container) : Fingerprints.UNKNOWN;
            note.print = now == frame.print() ? now : Fingerprints.UNKNOWN;
            if (frame.now().isEmpty() && !(container instanceof Collection<?>) && !(container instanceof Map<?, ?>)
                    && !(container instanceof Object[])) {
                this.remove(container, note);
                return;
            }
            note.takes = List.copyOf(frame.now());
        }
        if (frame.fresh()) {
            this.order.add(note);
            this.compact();
        }

        Set<String> under = new HashSet<>();
        fileUnder(under, note.takes);
        if (!completed) {
            fileUnder(under, frame.now());
        }
        note.under = Set.copyOf(under);
    }

    private static void fileUnder(Set<String> under, List<Runnable> takes) {
        for (Runnable take : takes) {
            if (take instanceof ElementTakes.NotedTake filed && filed.candidates() != null) {
                under.addAll(filed.candidates());
            } else {
                under.add(ANY);
            }
        }
    }

    synchronized void note(Runnable take) {
        Frame frame = this.open.peek();
        if (frame != null) {
            frame.now().add(take);
            return;
        }
        this.nowhere.add(take);
    }

    synchronized List<Runnable> findTakesFor(Collection<String> mods, boolean all, List<Object> containers) {
        this.expunge();
        List<Runnable> found = new ArrayList<>();
        for (Note note : this.order) {
            Object container = note.findFiled();
            if (container == null) {
                continue;
            }
            Set<String> under = note.under;
            if (all || under == null || under.contains(ANY) || !Collections.disjoint(under, mods)) {
                addFor(found, containers, container, note.takes, mods, all);
            }
        }
        addFor(found, containers, NOWHERE, this.nowhere, mods, all);
        return found;
    }

    private static void addFor(List<Runnable> found, List<Object> containers, Object container, List<Runnable> takes,
            Collection<String> mods, boolean all) {
        for (Runnable take : takes) {
            if (all || !(take instanceof ElementTakes.NotedTake filed) || filed.candidates() == null
                    || !Collections.disjoint(filed.candidates(), mods)) {
                found.add(take);
                containers.add(container);
            }
        }
    }

    synchronized List<Object> findContainers() {
        return this.toRead;
    }

    synchronized List<Object> findContainersUnder(Collection<String> mods) {
        if (mods.stream().allMatch(this.restored::containsKey)) {
            Set<Object> back = Collections.newSetFromMap(new IdentityHashMap<>());
            mods.forEach(mod -> back.addAll(this.restored.remove(mod)));
            this.lastForAnyHold = 0;
            return new ArrayList<>(back);
        }
        List<Object> found = new ArrayList<>();
        int forAnyHold = 0;
        for (Note note : this.order) {
            Object container = note.findFiled();
            Set<String> under = note.under;
            if (container != null && under != null && (under.contains(ANY) || !Collections.disjoint(under, mods))) {
                found.add(container);
                if (Collections.disjoint(under, mods)) {
                    forAnyHold++;
                }
            }
        }
        this.lastForAnyHold = forAnyHold;
        return found;
    }

    synchronized boolean isUnchanged(Object container) {
        Note note = this.notes.get(container);
        if (note == null || note.print == Fingerprints.UNKNOWN) {
            return false;
        }
        long now = Fingerprints.of(container);
        if (now != note.print) {
            this.printed = container;
            this.printedAs = now;
            return false;
        }
        this.leftUnchanged++;
        return true;
    }

    synchronized int countLeftUnchanged() {
        return this.leftUnchanged;
    }

    synchronized boolean isReadAgain(Object container) {
        Note note = this.notes.get(container);
        return note != null && note.readAgain;
    }

    synchronized void markReadAgain(Object container) {
        Note note = this.notes.get(container);
        if (note != null) {
            note.readAgain = true;
        }
    }

    synchronized void forgetChanged(Collection<Object> containers) {
        for (Object container : containers) {
            Note note = this.notes.get(container);
            if (note != null) {
                note.readAgain = false;
                note.print = Fingerprints.UNKNOWN;
            }
        }
    }

    synchronized void finishEvery() {
        this.order.forEach(note -> note.readAgain = false);
    }

    synchronized void noteRestored(String mod, @Nullable Object container) {
        if (container != null) {
            this.restored.computeIfAbsent(mod, any -> Collections.newSetFromMap(new MapMaker().weakKeys().makeMap()))
                    .add(container);
        }
    }

    synchronized int countLastForAnyHold() {
        return this.lastForAnyHold;
    }

    private List<Object> listContainers() {
        List<Object> found = new ArrayList<>();
        for (Note note : this.order) {
            Object container = note.findFiled();
            if (container != null && !note.ofWorld) {
                found.add(container);
            }
        }
        return found;
    }

    synchronized void markWrapped(Object container, int steps) {
        Note note = this.notes.get(container);
        if (note != null) {
            note.wrapped = steps;
        }
    }

    synchronized int findWrapped(Object container) {
        Note note = this.notes.get(container);
        return note == null ? 0 : note.wrapped;
    }

    synchronized Origin findOrigin(Object container) {
        Note note = this.notes.get(container);
        return note == null ? Origin.NONE : note.origin;
    }

    synchronized boolean hasReadStatics(Class<?> owner) {
        return this.staticsRead.contains(owner);
    }

    synchronized void markStaticsRead(Class<?> owner) {
        this.staticsRead.add(owner);
    }

    synchronized int countTakes() {
        return this.notes.values().stream().mapToInt(note -> note.takes.size()).sum() + this.nowhere.size();
    }

    synchronized int countContainers() {
        return this.notes.size();
    }

    private void remove(Object container, Note note) {
        this.notes.remove(container, note);

        note.clear();
        note.filed = false;
        note.takes = List.of();
        note.under = null;
        note.origin = Origin.NONE;
        this.dead++;
    }

    private void expunge() {
        for (Reference<?> cleared = this.gone.poll(); cleared != null; cleared = this.gone.poll()) {
            Note note = (Note) cleared;
            note.filed = false;
            note.takes = List.of();
            note.under = null;
            note.origin = Origin.NONE;
            this.dead++;
        }
        this.compact();
    }

    private void compact() {
        if (this.dead > 1024 && this.dead * 4 > this.notes.size()) {
            this.order.removeIf(note -> note.findFiled() == null);
            this.dead = 0;
        }
    }
}
