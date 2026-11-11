package dev.moon.hotwire.fabric.registration;

import com.google.common.collect.MapMaker;
import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.registration.Fingerprints;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

final class CollectionNotes {
    private static final Object NOWHERE = new Object();

    private static final class Frame {
        private final Object container;
        private final @Nullable Note note;
        private final List<Runnable> before;
        private final List<Runnable> now;
        private final boolean fresh;
        private final long print;
        private final Origin origin;
        private final boolean ofWorld;
        private final Set<String> readers;
        private int wrapped;
        private boolean lookedInto;

        private Frame(Object container, @Nullable Note note, List<Runnable> before, List<Runnable> now, boolean fresh,
                long print, Origin origin, boolean ofWorld, Set<String> readers) {
            this.container = container;
            this.note = note;
            this.before = before;
            this.now = now;
            this.fresh = fresh;
            this.print = print;
            this.origin = origin;
            this.ofWorld = ofWorld;
            this.readers = readers;
        }
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
        private boolean filed = true;

        private Set<String> readers = TakeReach.NOBODY;

        private int wrapped;

        private boolean lookedInto;

        private int unchangedIn;

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

    private final Map<Class<?>, Long> staticsPrints = new MapMaker().weakKeys().makeMap();

    private final Map<Object, Holder> holders = new MapMaker().weakKeys().makeMap();

    record Holder(@Nullable String staticsOf, Set<String> readers, long print, boolean ofWorld, boolean through) {
    }

    private List<Note> toRead = List.of();
    private final Deque<Frame> open = new ArrayDeque<>();

    private boolean inWorld;
    private int leftUnchanged;

    private long printedUnder = Long.MIN_VALUE;

    private boolean whole;

    private @Nullable Object printed;
    private long printedAs = Fingerprints.UNKNOWN;

    private int looks;

    synchronized void startLook(boolean newWorld, long owners, boolean whole) {
        this.looks++;
        this.whole = whole;
        this.dropCleared();
        this.printed = null;
        this.leftUnchanged = 0;
        this.dropLook();
        if (owners != this.printedUnder) {
            this.notes.values().forEach(note -> note.print = Fingerprints.UNKNOWN);
            this.printedUnder = owners;
        }
        if (newWorld) {
            for (Note note : this.order) {
                Object container = note.findFiled();
                if (container != null && note.ofWorld) {
                    this.remove(container, note);
                }
            }
            this.order.removeIf(note -> note.findFiled() == null);
            this.dead = 0;

            this.holders.values().removeIf(Holder::ofWorld);
        }
        this.nowhere = new ArrayList<>();
        this.toRead = List.copyOf(this.order);
    }

    synchronized void dropLook() {
        this.toRead = List.of();
        while (!this.open.isEmpty()) {
            Frame frame = this.open.pop();
            Note note = frame.note;
            if (note == null) {
                continue;
            }
            if (frame.fresh) {
                this.remove(frame.container, note);
            } else {
                note.takes = frame.before;
            }
        }
    }

    synchronized void setInWorld(boolean inWorld) {
        this.inWorld = inWorld;
    }

    synchronized boolean isInWorld() {
        return this.inWorld;
    }

    @SuppressWarnings("ReferenceEquality")
    synchronized void begin(Object container, Origin origin, Set<String> readers) {
        this.dropCleared();
        Frame outer = this.open.peek();
        if (outer != null && outer.container == container) {
            this.printed = null;
            this.open.push(outer);
            return;
        }
        Note note = this.notes.get(container);
        boolean fresh = note == null;
        if (note == null && isCollection(container)) {
            note = new Note(container, origin, this.gone);
            this.notes.put(container, note);
        }
        List<Runnable> before = note == null ? List.of() : note.takes;
        List<Runnable> now = new ArrayList<>();

        boolean anyOff = !before.isEmpty() && !Tripwire.findWatched().isEmpty();
        for (Runnable take : anyOff ? before : List.<Runnable>of()) {
            if (take instanceof ElementTakes.NotedTake filed && (filed.candidates() == null
                    || filed.candidates().stream().anyMatch(Tripwire::isWatched))) {
                now.add(take);
            }
        }
        if (note != null) {
            note.takes = now;
            note.origin = origin;
            note.ofWorld |= this.inWorld;
            note.readers = TakeReach.join(note.readers, readers);
        }
        long print = this.printed == container ? this.printedAs : Fingerprints.of(container);
        this.printed = null;
        this.open.push(new Frame(container, note, before, now, fresh, print, origin, this.inWorld, readers));
    }

    @SuppressWarnings("ReferenceEquality")
    synchronized boolean isBeingRead(Object container) {
        Frame top = this.open.peek();
        return top != null && top.container == container;
    }

    @SuppressWarnings("ReferenceEquality")
    synchronized void end(Object container, boolean completed) {
        Frame frame = this.open.peek();
        if (frame == null || frame.container != container) {
            return;
        }
        this.open.pop();
        if (frame == this.open.peek()) {
            return;
        }
        Note note = frame.note;
        if (note == null) {
            if (completed && frame.now.isEmpty()) {
                return;
            }
            note = new Note(container, frame.origin, this.gone);
            note.ofWorld = frame.ofWorld;
            note.wrapped = frame.wrapped;
            note.lookedInto = frame.lookedInto;
            note.readers = frame.readers;
            this.notes.put(container, note);
        }
        if (!completed) {
            note.takes = frame.before;
        } else {
            long now = Fingerprints.of(container);
            note.print = now == frame.print ? now : Fingerprints.UNKNOWN;
            if (frame.now.isEmpty() && !isCollection(container)) {
                this.remove(container, note);
                return;
            }
            note.takes = List.copyOf(frame.now);
        }
        if (frame.fresh) {
            this.order.add(note);
            this.pruneOrder();
        }

        Set<String> under = new HashSet<>();
        addCandidates(under, note.takes);
        if (!completed) {
            addCandidates(under, frame.now);
        }
        note.under = Set.copyOf(under);
    }

    private static void addCandidates(Set<String> under, List<Runnable> takes) {
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
            frame.now.add(take);
            return;
        }
        this.nowhere.add(take);
    }

    synchronized List<Runnable> findTakesFor(Collection<String> mods, boolean all, List<Object> containers) {
        this.dropCleared();
        List<Runnable> found = new ArrayList<>();
        Predicate<String> off = mod -> mods.contains(mod) || Tripwire.isWatched(mod);

        IdentityHashMap<Set<String>, Boolean> unread = new IdentityHashMap<>();
        IdentityHashMap<Set<String>, Boolean> uncalled = new IdentityHashMap<>();
        this.lastLeft = 0;
        this.leftIn.clear();
        for (Note note : this.order) {
            Object container = note.findFiled();
            if (container == null) {
                continue;
            }
            Set<String> under = note.under;
            if (all || under == null || under.contains(ANY) || !Collections.disjoint(under, mods)) {
                boolean reachable = all || !unread.computeIfAbsent(note.readers,
                        readers -> TakeReach.isAllOff(readers, off));
                this.addFor(found, containers, container, note.takes, mods, all, reachable, uncalled, off);
            }
        }
        this.addFor(found, containers, NOWHERE, this.nowhere, mods, all, true, uncalled, off);
        return found;
    }

    private void addFor(List<Runnable> found, List<Object> containers, Object container, List<Runnable> takes,
            Collection<String> mods, boolean all, boolean reachable, IdentityHashMap<Set<String>, Boolean> uncalled,
            Predicate<String> off) {
        for (Runnable take : takes) {
            if (all || !(take instanceof ElementTakes.NotedTake filed) || filed.candidates() == null
                    || !Collections.disjoint(filed.candidates(), mods)) {
                if (!reachable && take instanceof ElementTakes.NotedTake filed && filed.calledThrough() != null
                        && uncalled.computeIfAbsent(filed.calledThrough(),
                                one -> TakeReach.isCallableOnlyByOff(one, off))) {
                    this.lastLeft++;
                    this.leftIn.add(container);
                    continue;
                }
                found.add(take);
                containers.add(container);
            }
        }
    }

    private int lastLeft;
    private final Set<Object> leftIn = Collections.newSetFromMap(new MapMaker().weakKeys().makeMap());

    synchronized int countLastLeft() {
        return this.lastLeft;
    }

    synchronized List<Runnable> findTakesRevealed(Collection<String> coming, List<Object> containers) {
        this.dropCleared();
        Predicate<String> before = mod -> coming.contains(mod) || Tripwire.isWatched(mod);
        Predicate<String> after = mod -> !coming.contains(mod) && Tripwire.isWatched(mod);
        List<Runnable> found = new ArrayList<>();
        for (Note note : this.order) {
            Object container = note.findFiled();
            if (container == null || note.takes.isEmpty() || !TakeReach.isAllOff(note.readers, before)) {
                continue;
            }
            boolean stillUnread = TakeReach.isAllOff(note.readers, after);
            for (Runnable take : note.takes) {
                if (take instanceof ElementTakes.NotedTake filed && filed.calledThrough() != null
                        && TakeReach.isCallableOnlyByOff(filed.calledThrough(), before)
                        && !(stillUnread && TakeReach.isCallableOnlyByOff(filed.calledThrough(), after))
                        && (filed.candidates() == null || filed.candidates().stream().anyMatch(after))) {
                    found.add(take);
                    containers.add(container);
                }
            }
        }
        return found;
    }

    synchronized Set<String> findReaders(Object container) {
        Note note = this.notes.get(container);
        return note == null || note.readers.isEmpty() ? TakeReach.GAME : note.readers;
    }

    synchronized long findPrint(Object container) {
        Note note = this.notes.get(container);
        return note == null ? Fingerprints.UNKNOWN : note.print;
    }

    synchronized boolean isReadBy(Object container, Set<String> readers) {
        Note note = this.notes.get(container);
        return note != null && !note.readers.isEmpty() && note.readers.equals(TakeReach.join(note.readers, readers));
    }

    synchronized List<Object> findChanged() {
        List<Object> changed = new ArrayList<>();
        int at = 0;

        for (Note note : this.toRead) {
            if ((++at & STOP_EVERY) == 0) {
                ElementLook.stopIfAsked();
            }
            Object container = note.findFiled();
            if (container == null || GameRegistrations.isOfOffMod(container.getClass())) {
                continue;
            }
            if (!this.whole && note.print != Fingerprints.UNKNOWN && (isImmutable(container.getClass())
                    || note.print == Fingerprints.of(container))) {
                this.leftUnchanged++;
                note.unchangedIn = this.looks;
            } else {
                changed.add(container);
            }
        }
        return changed;
    }

    static boolean isImmutable(Class<?> type) {
        String name = type.getName();
        return name.startsWith(JDK_IMMUTABLE) || name.startsWith(JDK_EMPTY) || name.startsWith(JDK_SINGLETON)
                || (name.startsWith("com.google.common.collect.") && (name.contains("Immutable")
                        || name.startsWith("com.google.common.collect.Singleton")))
                || name.startsWith("kotlin.collections.Empty");
    }

    private static final String JDK_IMMUTABLE = findFamily(List.of().getClass());
    private static final String JDK_EMPTY = findFamily(Collections.emptyList().getClass()) + "Empty";
    private static final String JDK_SINGLETON = findFamily(Collections.singletonList(0).getClass()) + "Singleton";

    private static String findFamily(Class<?> type) {
        String name = type.getName();
        return name.substring(0, name.indexOf('$') + 1);
    }

    private static final int STOP_EVERY = 4095;

    synchronized void forgetPrint(@Nullable Object container) {
        Note note = container == null ? null : this.notes.get(container);
        if (note != null) {
            note.print = Fingerprints.UNKNOWN;
        }
    }

    synchronized boolean isUnchanged(Object container) {
        Note note = this.notes.get(container);
        if (this.whole || note == null || note.print == Fingerprints.UNKNOWN) {
            return false;
        }
        if (isImmutable(container.getClass()) || note.unchangedIn == this.looks) {
            return true;
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

    void notePrintsAfter(Collection<Object> containers, Runnable change) {
        Set<Object> current = Collections.newSetFromMap(new IdentityHashMap<>());
        synchronized (this) {
            for (Object container : containers) {
                Note note = this.notes.get(container);
                if (note != null && note.print != Fingerprints.UNKNOWN && note.print == Fingerprints.of(container)) {
                    current.add(container);
                }
            }
        }
        try {
            change.run();
        } finally {
            synchronized (this) {
                for (Object container : containers) {
                    Note note = this.notes.get(container);
                    if (note != null) {
                        note.print = current.contains(container) ? Fingerprints.of(container) : Fingerprints.UNKNOWN;
                    }
                }
            }
        }
    }

    synchronized void finishEvery() {
        this.toRead = List.of();
    }

    @SuppressWarnings("ReferenceEquality")
    synchronized void markLookedInto(Object container) {
        Frame frame = this.open.peek();
        Note note = frame != null && frame.container == container ? frame.note : this.notes.get(container);
        if (note != null) {
            note.lookedInto = true;
        } else if (frame != null && frame.container == container) {
            frame.lookedInto = true;
        }
    }

    synchronized boolean isLookedInto(Object container) {
        Note note = this.notes.get(container);
        return note != null && note.lookedInto;
    }

    synchronized @Nullable Holder findHolder(Object holder) {
        return this.holders.get(holder);
    }

    @SuppressWarnings("ReferenceEquality")
    synchronized void markWrapped(Object container, int steps) {
        Frame frame = this.open.peek();
        Note note = frame != null && frame.container == container ? frame.note : this.notes.get(container);
        if (note != null) {
            note.wrapped = steps;
        } else if (frame != null && frame.container == container) {
            frame.wrapped = steps;
        }
    }

    synchronized int findWrapped(Object container) {
        Note note = this.notes.get(container);
        return note == null ? 0 : note.wrapped;
    }

    record NoteSummary(int takes, Set<String> under, Set<String> readers, boolean leftByOff) {
    }

    synchronized IdentityHashMap<Object, Holder> findWatched() {
        IdentityHashMap<Object, Holder> watched = new IdentityHashMap<>();
        this.holders.forEach(watched::put);
        return watched;
    }

    synchronized IdentityHashMap<Object, NoteSummary> findSummaries() {
        IdentityHashMap<Object, NoteSummary> known = new IdentityHashMap<>();
        this.notes.forEach((container, note) -> {
            Set<String> under = new HashSet<>();
            addCandidates(under, note.takes);
            known.put(container, new NoteSummary(note.takes.size(), under, note.readers,
                    this.leftIn.contains(container)));
        });
        return known;
    }

    synchronized boolean isOfWorld(Object container) {
        Note note = this.notes.get(container);
        return note != null && note.ofWorld;
    }

    synchronized Origin findOrigin(Object container) {
        Note note = this.notes.get(container);
        return note == null ? Origin.NONE : note.origin;
    }

    synchronized boolean isStaticsUnchanged(Class<?> owner, long print) {
        Long read = this.staticsPrints.get(owner);
        return !this.whole && read != null && read == print && print != Fingerprints.UNKNOWN;
    }

    synchronized void noteStatics(Class<?> owner, long print) {
        this.staticsPrints.put(owner, print);
    }

    synchronized void forgetFieldsOf(Object holder) {
        if (holder instanceof Class<?> owner) {
            this.staticsPrints.remove(owner);
            return;
        }
        Holder read = this.holders.get(holder);

        this.holders.put(holder, read == null ? new Holder(null, TakeReach.GAME, Fingerprints.UNKNOWN, false, true)
                : new Holder(read.staticsOf(), read.readers(), Fingerprints.UNKNOWN, read.ofWorld(), read.through()));
    }

    synchronized void forgetHolder(Object holder) {
        this.holders.remove(holder);
    }

    synchronized List<Class<?>> findStaticsRead() {
        return List.copyOf(this.staticsPrints.keySet());
    }

    synchronized boolean isWhole() {
        return this.whole;
    }

    synchronized void noteHolder(Object holder, Holder read) {
        this.holders.put(holder, read);
    }

    synchronized void noteHolderInside(Object holder, Holder read) {
        Holder had = this.holders.get(holder);
        if (had == null || !had.through()) {
            this.holders.put(holder, read);
        }
    }

    synchronized List<Map.Entry<Object, Holder>> findHolders() {
        return List.copyOf(this.holders.entrySet());
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

    private static boolean isCollection(Object container) {
        return container instanceof Collection<?> || container instanceof Map<?, ?> || container instanceof Object[];
    }

    private void dropCleared() {
        for (Reference<?> cleared = this.gone.poll(); cleared != null; cleared = this.gone.poll()) {
            Note note = (Note) cleared;
            note.filed = false;
            note.takes = List.of();
            note.under = null;
            note.origin = Origin.NONE;
            this.dead++;
        }
        this.pruneOrder();
    }

    private void pruneOrder() {
        if (this.dead > 1024 && this.dead * 4 > this.notes.size()) {
            this.order.removeIf(note -> note.findFiled() == null);
            this.dead = 0;
        }
    }
}
