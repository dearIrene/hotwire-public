package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.registration.Fingerprints;
import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class CollectionReferences implements FixedLists.Walk {
    private static final Logger LOGGER = LoggerFactory.getLogger(CollectionReferences.class);

    private static final int LARGEST = 100_000;

    static final int WRAPPERS_IN = 512;
    private final Map<String, List<Removal>> removals = new HashMap<>();
    private final StaticRecomputer.Writer writer;

    private IdentityHashMap<Object, Set<String>> seen = new IdentityHashMap<>();

    private Set<String> reader = TakeReach.GAME;
    List<String> mods = List.of();

    private ElementOwners owners = new ElementOwners(List.of(), Set.of());
    private Map<String, List<Removal>> mine = Map.of();

    @Nullable String readingStaticsOf;

    private @Nullable Object collecting;

    private final CheckReport checkReport = new CheckReport();
    private ElementTakes.@Nullable Answers lastAnswered;

    private Set<Object> readThrough = Collections.newSetFromMap(new IdentityHashMap<>());

    private WalkReport report = new WalkReport();

    private ElementTakes.@Nullable Answers prepared;
    private List<String> preparedFor = List.of();

    private static final boolean NO_PRINTS = System.getProperty("hotwire.noFingerprints") != null;
    private boolean inSmall;

    final TakeCandidates candidates = new TakeCandidates();

    synchronized void forgetPrepared() {
        this.prepared = null;
    }

    void forgetClosedWorld() {
        this.forgetPrepared();
        this.owners.getWrappers().forgetObjects();
        this.candidates.forgetObjects();
    }

    private final Map<String, Integer> cannotChange = new ConcurrentHashMap<>();

    final ElementLook look = new ElementLook();

    CollectionReferences(StaticRecomputer.Writer writer) {
        this.writer = writer;
    }

    synchronized void takeOut(List<String> mods, Collection<Object> holders, Collection<Class<?>> statics,
            HandedSearch handed) {
        Tripwire.runAsHotwire(() -> this.takeOutNow(mods, holders, statics, handed));
    }

    private void takeOutNow(List<String> mods, Collection<Object> holders, Collection<Class<?>> statics,
            HandedSearch handed) {
        ElementTakes.Answers ready = this.prepared != null && this.preparedFor.equals(mods) ? this.prepared : null;
        this.prepared = null;

        if (ready == null) {
            this.owners = new ElementOwners(List.of(), Set.of());
        }
        List<Runnable> ahead = ready != null ? List.of() : this.look.open(mods);
        try {
            if (ready != null) {
                this.takeAll(ready, mods);
                this.takeWrappedIn(holders);
            } else if (ahead == null) {
                this.candidates.beginWalk();
                try {
                    this.walk(mods, holders, statics, handed);
                } finally {
                    this.candidates.endWalk();
                }
                this.look.close();
            } else {
                this.begin(mods);
                this.takeAll(ElementTakes.prepare(ahead, mods, this.look.takeContainersOfTakes()), mods);
                this.takeWrappedIn(holders);
            }
        } catch (RuntimeException | LinkageError e) {
            this.look.drop();
            throw e;
        } finally {
            this.endRemoval(mods);
        }
    }

    private void endRemoval(List<String> mods) {
        this.mine.forEach((mod, out) -> {
            out.forEach(Removal::resetHolder);
            if (!out.isEmpty()) {
                this.removals.computeIfAbsent(mod, any -> new ArrayList<>()).addAll(out);
                LOGGER.info("[Hotwire] {} of {}'s objects were taken out of collections others keep: {}",
                        out.size(), mod, out.stream().map(Removal::describe).distinct().toList());
            }
        });
        if (!this.cannotChange.isEmpty()) {
            LOGGER.info("[Hotwire] collections that cannot change keep {}'s objects: {}", mods,
                    new TreeMap<>(this.cannotChange));
            this.cannotChange.clear();
        }

        this.seen = new IdentityHashMap<>();
        this.readThrough = Collections.newSetFromMap(new IdentityHashMap<>());
        this.mods = List.of();
        this.owners = new ElementOwners(List.of(), Set.of());
        this.mine = Map.of();
    }

    boolean visit(Object value) {
        Set<String> before = this.seen.get(value);
        if (before == null) {
            this.seen.put(value, this.reader);
            return true;
        }
        Set<String> now = TakeReach.join(before, this.reader);
        if (now.equals(before)) {
            return false;
        }
        this.seen.put(value, now);
        return true;
    }

    Set<String> getReader() {
        return this.reader;
    }

    void leave(Set<String> was) {
        this.reader = was;
    }

    Set<String> enter(Object object) {
        Set<String> was = this.reader;
        if (ElementLook.isLooking()) {
            this.reader = TakeReach.findReaders(object.getClass(), was);
        }
        return was;
    }

    synchronized String describeTakesFor(List<String> mods, IdentityHashMap<Object, CollectionNotes.NoteSummary> known,
            IdentityHashMap<Object, CollectionNotes.Holder> watched) {
        List<Object> containers = new ArrayList<>();
        List<Runnable> takes = this.look.getNotes().findTakesFor(mods, false, containers);
        Map<String, Integer> found = new TreeMap<>();

        this.begin(mods);
        try {
            for (int at = 0; at < takes.size(); at++) {
                Object container = containers.get(at);
                if (takes.get(at) instanceof ElementTakes.NotedTake noted && noted.whose().get() != null
                        && this.holdsOwned(container) && this.checkReport.isOnlyThroughNotes(container)) {
                    this.onlyThroughNotes.merge(container.getClass().getSimpleName() + " of " + sizeOf(container)
                            + " from " + this.look.getNotes().findOrigin(container).staticsOf(), 1, Integer::sum);
                } else if (takes.get(at) instanceof ElementTakes.NotedTake noted && noted.whose().get() != null
                        && this.holdsOwned(container)) {
                    CollectionNotes.Origin origin = this.look.getNotes().findOrigin(container);
                    found.merge(container.getClass().getSimpleName() + " of " + sizeOf(container) + " from "
                            + origin.staticsOf() + (origin.field() == null ? "" : " " + origin.field()
                            .getDeclaringClass().getName() + "." + origin.field().getName())
                            + (this.look.getNotes().isOfWorld(container) ? " (world)" : "")
                            + CheckReport.describeKnown(known.get(container))
                            + CheckReport.describeAnswered(this.lastAnswered, container)
                            + CheckReport.describeFiled(noted)
                            + this.checkReport.describePath(container, known, watched) + " holding "
                            + this.findClassesOwned(container), 1, Integer::sum);
                }
            }
        } finally {
            this.endRemoval(mods);
        }
        return found.toString();
    }

    private final Map<String, Integer> onlyThroughNotes = new TreeMap<>();

    synchronized String takeOnlyThroughNotes() {
        String said = this.onlyThroughNotes.toString();
        this.onlyThroughNotes.clear();
        return said;
    }

    synchronized void traceReach(boolean on) {
        if (on) {
            this.checkReport.begin();
        } else {
            this.checkReport.endCheck();
        }
    }

    synchronized void traceLook(boolean on) {
        if (on) {
            this.checkReport.begin();
        } else {
            this.checkReport.endLook();
        }
    }

    private boolean holdsOwned(Object container) {
        for (Object item : findItems(container)) {
            if (item != null && this.owners.findOwnerOf(item, false) != null) {
                return true;
            }
        }
        return false;
    }

    private static List<?> findItems(Object container) {
        return new ArrayList<>(container instanceof Map<?, ?> map ? map.values()
                : container instanceof Collection<?> all ? all : container instanceof Object[] array
                ? Arrays.asList(array) : List.of());
    }

    private Set<String> findClassesOwned(Object container) {
        Set<String> found = new TreeSet<>();
        for (Object item : findItems(container)) {
            String owner = item == null || found.size() >= 4 ? null : this.owners.findOwnerOf(item, false);
            if (item != null && owner != null) {
                found.add(item.getClass().getName() + " of " + owner);
            }
        }
        return found;
    }

    private static final String ONECONFIG_NODE = "org.polyfrost.oneconfig.api.config.v1.Node";

    private static boolean isOneConfigNode(Class<?> type) {
        for (Class<?> at = type.getSuperclass(); at != null; at = at.getSuperclass()) {
            if (at.getName().equals(ONECONFIG_NODE)) {
                return true;
            }
        }
        return false;
    }

    synchronized void takeRevealed(Collection<String> coming) {
        Tripwire.runAsHotwire(() -> {
            List<String> stillOff = Tripwire.findWatched().stream().filter(mod -> !coming.contains(mod)).toList();
            if (stillOff.isEmpty()) {
                return;
            }
            List<Object> containers = new ArrayList<>();
            List<Runnable> revealed = this.look.getNotes().findTakesRevealed(coming, containers);
            if (revealed.isEmpty()) {
                return;
            }
            LOGGER.info("[Hotwire] {} coming on can reach {} objects of mods still off", coming, revealed.size());
            this.begin(stillOff);
            try {
                this.takeAll(ElementTakes.prepare(revealed, stillOff, containers), stillOff);
            } finally {
                this.endRemoval(stillOff);
            }
        });
    }

    private static final int WRAPPED_BELOW = 3;

    private void readStatic(FieldSlots.Slot field, Object value, boolean game) {
        CollectionNotes notes = ElementLook.isLooking() ? this.look.getNotes() : null;

        if (notes != null && !NO_PRINTS && notes.isReadBy(value, this.reader) && notes.isUnchanged(value)) {
            return;
        }
        if (notes != null) {
            notes.begin(value, new CollectionNotes.Origin(this.readingStaticsOf, game, field), this.reader);
        }
        boolean completed = false;
        if (this.checkReport.isOn()) {
            this.checkReport.enter("static " + field.getDeclaringClass().getName() + "." + field.getName());
        }
        try {
            if (game && value instanceof Map<?, ?> map && map.size() <= LARGEST) {
                this.readChanging(map, now -> NamespacedMaps.removeFrom(this, (Map<?, ?>) now));
            }
            this.collect(value);
            if (value instanceof List<?> list && list.size() <= LARGEST && CollectionParts.isFixedSize(list)
                    && field.getType().isAssignableFrom(List.class)) {
                FixedLists.removeFromField(this, field, null);
            }

            if (game && CollectionParts.isGameObject(value)) {
                this.noteWrappedInside(value, WRAPPED_BELOW);
            }

            if (!(value instanceof Collection<?>) && !(value instanceof Map<?, ?>) && !(value instanceof Object[])
                    && (ElementLook.isLooking() || this.owners.findOwnerAmong(value) == null)) {
                this.lookInto(value);
            }
            completed = true;
        } finally {
            this.checkReport.leave();
            if (notes != null) {
                notes.end(value, completed);
            }
        }
    }

    synchronized boolean prepare(List<String> mods) {
        boolean[] ready = new boolean[1];
        Tripwire.runAsHotwire(() -> ready[0] = this.prepareNow(mods));
        return ready[0];
    }

    private boolean prepareNow(List<String> mods) {
        this.prepared = null;
        if (ElementLook.isLooking()) {
            return false;
        }
        List<Runnable> ahead = this.look.open(mods);
        if (ahead == null) {
            return false;
        }
        this.begin(mods);
        long began = System.nanoTime();
        List<Object> containers = this.look.takeContainersOfTakes();
        ElementTakes.Answers made = ElementTakes.prepare(ahead, mods, containers);
        this.prepared = made;
        this.lastAnswered = ElementLook.isChecking() ? made : null;
        this.preparedFor = List.copyOf(mods);
        LOGGER.info("[Hotwire] {} of {} noted takes answered for {} ahead of the hold in {} ms; {} left in place, where"
                + " nothing on can reach them", made.takes().size(), ahead.size(), mods,
                (System.nanoTime() - began) / 1_000_000, this.look.getNotes().countLastLeft());
        return true;
    }

    private void takeWrappedIn(Collection<Object> holders) {
        for (Object holder : holders) {
            if (CollectionParts.isGameObject(holder) && this.owners.findOwnerAmong(holder) == null) {
                this.noteWrappedInside(holder, WRAPPED_BELOW);
            }
        }
    }

    private void takeAll(ElementTakes.Answers answers, List<String> mods) {
        this.look.getNotes().notePrintsAfter(answers.prints().keySet(),
                () -> ElementTakes.applyPrepared(answers, mods, this::describeOrigin));
    }

    private String describeOrigin(Object container) {
        return String.valueOf(this.look.getNotes().findOrigin(container).staticsOf());
    }

    private void begin(List<String> mods) {
        this.seen = new IdentityHashMap<>();
        this.readThrough = Collections.newSetFromMap(new IdentityHashMap<>());
        this.reader = TakeReach.GAME;
        this.mods = List.copyOf(mods);
        this.owners = new ElementOwners(mods, RegisteredContent.find());
        Map<String, List<Removal>> mine = new LinkedHashMap<>();
        mods.forEach(mod -> mine.put(mod, new ArrayList<>()));
        this.mine = mine;
    }

    private void walk(List<String> mods, Collection<Object> holders, Collection<Class<?>> statics,
            HandedSearch handed) {
        long began = System.nanoTime();
        this.begin(mods);
        this.report = new WalkReport();

        CollectionNotes notes = ElementLook.isLooking() ? this.look.getNotes() : null;
        long holdersBegan = System.nanoTime();
        if (notes != null) {
            notes.setInWorld(true);
        }

        Set<Object> looked = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object holder : holders) {
            ElementLook.stopIfAsked();
            if (!looked.add(holder)) {
                continue;
            }
            long holderBegan = System.nanoTime();
            if (this.owners.findOwnerAmong(holder) == null) {
                this.lookInto(holder);
                if (CollectionParts.isGameObject(holder)) {
                    this.noteWrappedInside(holder, WRAPPED_BELOW);
                }
            }
            this.report.timeHolder(holder.getClass(), holderBegan);
        }
        this.report.time(holdersBegan, holders.size() + " game objects");
        long handedBegan = System.nanoTime();
        for (Object holder : handed.findHolders()) {
            if (this.visit(holder)) {
                this.collect(holder);
            }
        }
        if (notes != null) {
            notes.setInWorld(false);
        }
        this.report.time(handedBegan, "what was handed to " + handed.consumers());

        List<Class<?>> ordered = new ArrayList<>(statics.size());
        List<Class<?>> ofMods = new ArrayList<>();
        for (Class<?> owner : statics) {
            (GameRegistrations.findModOf(owner) == null ? ordered : ofMods).add(owner);
        }
        ordered.addAll(ofMods);
        int unchanged = 0;
        for (Class<?> owner : ordered) {
            ElementLook.stopIfAsked();

            long print = notes == null ? Fingerprints.UNKNOWN : printFields(owner);
            if (notes != null && notes.isStaticsUnchanged(owner, print)) {
                unchanged++;
                continue;
            }
            long ownerBegan = System.nanoTime();
            boolean game = owner.getName().startsWith("net.minecraft.") || owner.getName().startsWith("com.mojang.");
            this.readingStaticsOf = GameRegistrations.findModOf(owner);
            this.reader = notes == null ? TakeReach.GAME : TakeReach.findReaders(owner, TakeReach.GAME);
            for (FieldSlots.Slot field : Fields.getReferenceFields(owner, true)) {
                Object value = CollectionParts.read(field, null);
                if (value != null && this.visit(value)) {
                    this.readStatic(field, value, game);
                }
            }
            if (notes != null) {
                notes.noteStatics(owner, print);
            }
            this.report.time(ownerBegan, owner.getName());
        }
        if (notes != null) {
            this.readNotedAgain(notes, unchanged);
        }
        this.readingStaticsOf = null;
        this.reader = TakeReach.GAME;
        this.report.end(ElementLook.isLooking(), mods, statics.size(), began, this.owners.getWrappers().describe(),
                this.candidates);
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    private static int sizeOf(Object container) {
        try {
            return container instanceof Collection<?> all ? all.size() : container instanceof Map<?, ?> map
                    ? map.size() : container instanceof Object[] array ? array.length : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private void readNotedAgain(CollectionNotes notes, int staticsUnchanged) {
        long began = System.nanoTime();
        int again = 0;
        List<Object> changed = notes.findChanged();
        long printed = System.nanoTime();

        Map<String, long[]> spent = new HashMap<>();
        for (Object container : changed) {
            ElementLook.stopIfAsked();
            if (this.seen.containsKey(container)) {
                continue;
            }
            this.reader = notes.findReaders(container);
            this.seen.put(container, this.reader);
            again++;
            long readBegan = System.nanoTime();
            CollectionNotes.Origin origin = notes.findOrigin(container);
            this.readingStaticsOf = origin.staticsOf();
            this.checkReport.enter(CheckReport.NOTES);
            try {
                if (origin.field() != null) {
                    this.readStatic(origin.field(), container, origin.game());
                } else if (!(container instanceof Collection<?>) && !(container instanceof Map<?, ?>)
                        && !(container instanceof Object[]) && notes.findWrapped(container) > 0) {
                    this.noteWrappedInside(container, notes.findWrapped(container));
                } else {
                    this.collect(container);
                }
            } finally {
                this.checkReport.leave();
                long[] cost = spent.computeIfAbsent(container.getClass().getSimpleName() + " of "
                        + (notes.isOfWorld(container) ? "the world" : origin.staticsOf()), any -> new long[2]);
                cost[0]++;
                cost[1] += System.nanoTime() - readBegan;
            }
        }
        long readAgain = System.nanoTime();
        int moved = 0;

        boolean whole = notes.isWhole();
        for (Map.Entry<Object, CollectionNotes.Holder> one : notes.findHolders()) {
            ElementLook.stopIfAsked();
            Object holder = one.getKey();
            long was = one.getValue().print();
            if (!whole && was != Fingerprints.UNKNOWN && was == printFields(holder)) {
                continue;
            }
            this.reader = one.getValue().readers();
            this.readingStaticsOf = one.getValue().staticsOf();

            boolean firstVisit = this.visit(holder);
            if (firstVisit || (one.getValue().through() && !this.readThrough.contains(holder))) {
                moved++;
                notes.setInWorld(one.getValue().ofWorld());
                this.checkReport.enter(CheckReport.NOTES);
                try {
                    this.lookInto(holder);
                } finally {
                    this.checkReport.leave();
                    notes.setInWorld(false);
                }
            }
        }
        long ended = System.nanoTime();
        LOGGER.info("[Hotwire] the look read {} collections of the notes again in {} ms ({} passed as unchanged), {}"
                + " objects whose fields moved, and passed the statics of {} classes unchanged; prints {} ms, reads"
                + " again {} ms, holders {} ms; the reads again took longest over {}", again,
                (ended - began) / 1_000_000, notes.countLeftUnchanged(), moved, staticsUnchanged,
                (printed - began) / 1_000_000, (readAgain - printed) / 1_000_000, (ended - readAgain) / 1_000_000,
                describeLongest(spent));
    }

    private static String describeLongest(Map<String, long[]> spent) {
        return spent.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1])).limit(6)
                .map(one -> one.getKey() + " " + one.getValue()[0] + " in " + one.getValue()[1] / 1_000_000 + " ms")
                .toList().toString();
    }

    void takeOrNote(String owner, @Nullable Set<String> candidates, Supplier<@Nullable String> asked,
            Consumer<String> take, @Nullable Object element) {
        this.takeOrNote(owner, candidates, asked, take, element, null);
    }

    private void takeOrNote(String owner, @Nullable Set<String> candidates, Supplier<@Nullable String> asked,
            Consumer<String> take, @Nullable Object element, @Nullable Set<String> calledThrough) {
        Set<String> under = this.candidates.withAll(this.candidates.withOwner(candidates, owner),
                this.owners.findRecordedCandidates(element));
        if (ElementLook.isLooking() && this.collecting != null && TakeReach.isNeverNeeded(owner, under, calledThrough,
                this.look.getNotes().findReaders(this.collecting))) {
            this.report.countOwnLeft();
            return;
        }
        boolean small = this.inSmall;
        Supplier<@Nullable String> whose = ElementTakes.timeAsk(() -> {
            boolean was = this.inSmall;
            this.inSmall = small;
            try {
                return asked.get();
            } finally {
                this.inSmall = was;
            }
        }, element == null ? "null" : element.getClass().getName());
        this.report.count(candidates != null);
        if (!ElementLook.isLooking() || !this.look.hasNoted(new ElementTakes.NotedTake(whose, take, under,
                calledThrough))) {
            take.accept(owner);
            this.look.getNotes().forgetPrint(this.collecting);
        } else {
            this.report.countNoted(owner, this.collecting == null ? "a field"
                    : this.collecting.getClass().getSimpleName() + " of " + this.readingStaticsOf);
        }
    }

    private boolean isOwnPlain(String mod, Object item) {
        return !this.inSmall && mod.equals(this.readingStaticsOf) && Fields.getRecordParts(item.getClass()).isEmpty()
                && !ValueOwners.isLambda(item) && GameRegistrations.findModOf(item.getClass()) != null
                && mod.equals(GameRegistrations.findModOf(item.getClass()));
    }

    @Override
    public List<Removal> getRemovalsOf(String mod) {
        return this.mine.computeIfAbsent(mod, any -> new ArrayList<>());
    }

    @Override
    public List<String> getMods() {
        return this.mods;
    }

    @Override
    public ElementOwners getOwners() {
        return this.owners;
    }

    @Override
    public boolean isInSmall() {
        return this.inSmall;
    }

    @Override
    public void setInSmall(boolean small) {
        this.inSmall = small;
    }

    @Override
    public ElementLook getLook() {
        return this.look;
    }

    @Override
    public StaticRecomputer.Writer getWriter() {
        return this.writer;
    }

    synchronized void putBack(Collection<String> mods) {
        List<Map.Entry<String, Removal>> back = new ArrayList<>();
        for (String mod : mods) {
            List<Removal> removed = this.removals.remove(mod);
            if (removed != null) {
                removed.forEach(one -> back.add(Map.entry(mod, one)));
            }
        }
        back.sort(Comparator.comparingInt(one -> one.getValue() instanceof Removal.InList list ? list.index() : 0));
        Set<Object> containers = Collections.newSetFromMap(new IdentityHashMap<>());
        back.forEach(one -> {
            if (one.getValue().container() != null) {
                containers.add(one.getValue().container());
            }
        });

        this.look.getNotes().notePrintsAfter(containers, () -> {
            for (Map.Entry<String, Removal> one : back) {
                try {
                    one.getValue().restore();
                } catch (RuntimeException e) {
                    LOGGER.warn("[Hotwire] one of {}'s objects could not go back into {}", one.getKey(),
                            one.getValue().describe(), e);
                }
            }
        });
    }

    void lookInto(Object holder) {
        this.readThrough.add(holder);
        Set<String> was = this.enter(holder);
        this.checkReport.enter(holder);
        try {
            this.lookIntoFields(holder);
        } finally {
            this.checkReport.leave();
            this.reader = was;
        }
    }

    private void noteHolder(Object holder) {
        if (!ElementLook.isLooking()) {
            return;
        }
        if (findMoving(holder.getClass(), false).isEmpty()) {
            this.look.getNotes().forgetHolder(holder);
            return;
        }
        this.look.getNotes().noteHolder(holder, new CollectionNotes.Holder(this.readingStaticsOf, this.reader,
                printFields(holder), this.look.getNotes().isInWorld(), true));
    }

    private void noteHolderInside(Object holder) {
        if (!ElementLook.isLooking() || findMoving(holder.getClass(), false).isEmpty()) {
            return;
        }
        this.look.getNotes().noteHolderInside(holder, new CollectionNotes.Holder(this.readingStaticsOf, this.reader,
                printFields(holder), this.look.getNotes().isInWorld(), false));
    }

    static long printFields(Object holder) {
        boolean statics = holder instanceof Class<?>;
        List<FieldSlots.Slot> fields = findMoving(statics ? (Class<?>) holder : holder.getClass(), statics);
        List<@Nullable Object> values = new ArrayList<>(fields.size());
        for (FieldSlots.Slot field : fields) {
            values.add(CollectionParts.read(field, statics ? null : holder));
        }
        return Fingerprints.of(values);
    }

    static boolean canMove(Class<?> owner) {
        return !findMoving(owner, true).isEmpty();
    }

    private record PrintFieldList(List<FieldSlots.Slot> from, List<FieldSlots.Slot> moving) {
    }

    private static final ClassValue<PrintFieldList[]> PRINT_FIELDS = new ClassValue<>() {
        @Override
        protected PrintFieldList[] computeValue(Class<?> type) {
            return new PrintFieldList[2];
        }
    };

    @SuppressWarnings("ReferenceEquality")
    static List<FieldSlots.Slot> findMoving(Class<?> type, boolean statics) {
        List<FieldSlots.Slot> all = Fields.getReferenceFields(type, statics);
        PrintFieldList[] slots = PRINT_FIELDS.get(type);
        int at = statics ? 1 : 0;
        PrintFieldList known = slots[at];
        if (known == null || known.from() != all) {
            known = new PrintFieldList(all, all.stream()
                    .filter(field -> !Modifier.isFinal(field.getModifiers())).toList());
            slots[at] = known;
        }
        return known.moving();
    }

    private void lookIntoFields(Object holder) {
        if (holder instanceof Registry<?>) {
            return;
        }

        if (isOneConfigNode(holder.getClass())) {
            return;
        }

        if (Proxy.isProxyClass(holder.getClass())) {
            Object handler = Proxy.getInvocationHandler(holder);
            if (this.visit(handler)) {
                this.lookInto(handler);
            }
            return;
        }
        this.noteHolder(holder);
        for (FieldSlots.Slot field : Fields.getReferenceFields(holder.getClass(), false)) {
            Object value = CollectionParts.read(field, holder);
            if ((value instanceof Collection<?> || value instanceof Map<?, ?> || value instanceof Object[])
                    && this.visit(value)) {
                LazyFields.noteHolder(value, holder);
                this.collect(value);

                if (value instanceof List<?> list && list.size() <= LARGEST && CollectionParts.isFixedSize(list)
                        && field.getType().isAssignableFrom(List.class)) {
                    FixedLists.removeFromField(this, field, holder);
                }
            } else if (value != null && Wrappers.isLookedInto(value) && !isOneConfigNode(value.getClass())
                    && this.visit(value)) {
                Set<String> was = this.enter(value);
                this.checkReport.enter(value);
                try {
                    this.noteHolderInside(value);
                    for (FieldSlots.Slot inner : Fields.getReferenceFields(value.getClass(), false)) {
                        Object innerValue = CollectionParts.read(inner, value);
                        if ((innerValue instanceof Collection<?> || innerValue instanceof Map<?, ?>
                                || innerValue instanceof Object[]) && this.visit(innerValue)) {
                            this.collect(innerValue);
                        }
                    }
                } finally {
                    this.checkReport.leave();
                    this.reader = was;
                }
            }
        }
    }

    private static final int DEEPEST = 64;
    private int depth;

    private void collect(Object value) {
        ElementLook.stopIfAsked();
        if (this.owners.findOwnerAmong(value) != null || GameRegistrations.isOfOffMod(value.getClass())
                || this.depth >= DEEPEST) {
            return;
        }
        this.depth++;
        this.checkReport.enter(value);
        try {
            this.collectWithin(value);
        } finally {
            this.checkReport.leave();
            this.depth--;
        }
    }

    private void collectWithin(Object value) {
        CollectionNotes notes = ElementLook.isLooking() ? this.look.getNotes() : null;

        if (notes != null && !NO_PRINTS && !notes.isBeingRead(value) && notes.isReadBy(value, this.reader)
                && notes.isUnchanged(value)) {
            return;
        }
        this.readChanging(value, this::collectOnce);
    }

    private void readChanging(Object value, Consumer<Object> read) {
        CollectionParts.readChanging(value, read, this.look, this.mods);
    }

    @SuppressWarnings("unchecked")
    private void collectOnce(Object value) {
        boolean outer = this.inSmall;
        CollectionNotes notes = ElementLook.isLooking() ? this.look.getNotes() : null;
        if (notes != null) {
            notes.begin(value, CollectionNotes.Origin.of(this.readingStaticsOf), this.reader);
        }
        Object collectingBefore = this.collecting;
        this.collecting = value;
        boolean completed = false;
        try {
            this.inSmall = switch (value) {
                case Collection<?> all -> all.size() <= WRAPPERS_IN;
                case Map<?, ?> map -> map.size() <= WRAPPERS_IN;
                default -> false;
            };
            switch (value) {
                case List<?> list when list.size() <= LARGEST -> {
                    List<Object> items = (List<Object>) list;
                    boolean fixed = CollectionParts.isFixedSize(items);
                    for (int index = items.size() - 1; index >= 0; index--) {
                        Object item = items.get(index);
                        String mod = item == null ? null : this.owners.findOwnerOf(item, this.inSmall);
                        if (mod != null && this.isOwnPlain(mod, item)) {
                            this.report.countOwnLeft();
                        } else if (mod != null && fixed) {
                            this.cannotChange.merge(value.getClass().getName(), 1, Integer::sum);
                            break;
                        } else if (mod != null) {
                            this.takeOrNote(mod, this.candidates.find(item, this.inSmall),
                                    ElementTakes.askWeak(item, one -> this.owners.findOwnerOf(one, this.inSmall)),
                                    ElementTakes.makeWeak(items, item, (now, one, owner) ->
                                            ElementTakes.takeFromList(now, one, this.getRemovalsOf(owner))), item,
                                    TakeReach.findCalledThrough(item));
                        } else if (item != null) {
                            this.followLambda(item);
                            if (!(item instanceof Collection<?>) && !(item instanceof Map<?, ?>)
                                    && !Wrappers.isOfAnotherMod(item.getClass())) {
                                this.noteWrappedInside(item, WRAPPED_BELOW);
                            }
                        }
                    }
                }
                case Collection<?> collection when collection.size() <= LARGEST -> {
                    Collection<Object> items = (Collection<Object>) collection;
                    for (Object item : Wrappers.readItems(items)) {
                        String mod = item == null ? null : this.owners.findOwnerOf(item, this.inSmall);
                        if (mod != null && this.isOwnPlain(mod, item)) {
                            this.report.countOwnLeft();
                        } else if (mod != null) {
                            this.takeOrNote(mod, this.candidates.find(item, this.inSmall),
                                    ElementTakes.askWeak(item, one -> this.owners.findOwnerOf(one, this.inSmall)),
                                    ElementTakes.makeWeak(items, item, (now, one, owner) ->
                                            ElementTakes.takeFromCollection(now, one, this.getRemovalsOf(owner))),
                                    item, TakeReach.findCalledThrough(item));
                        }
                    }
                }
                case Object[] array when array.length <= LARGEST -> {
                    Class<?> slot = array.getClass().getComponentType();
                    for (int index = 0; index < array.length; index++) {
                        Object item = array[index];
                        String whole = item == null ? null : this.owners.findWholeOwnerOfSlot(item);

                        if (whole != null && !StandIns.fits(slot)) {
                            whole = null;
                        }
                        String parts = item == null || whole != null ? null : this.owners.findPartsOwnerOfSlot(item);
                        Object standIn = whole != null ? StandIns.of(slot)
                                : parts != null ? CollectionParts.withStandIns(parts, Objects.requireNonNull(item))
                                : null;
                        if (item != null && standIn != null) {
                            String found = whole != null ? whole : Objects.requireNonNull(parts);
                            int at = index;
                            this.takeOrNote(found, this.candidates.find(item, true),

                                    ElementTakes.askWeak(item, one -> this.owners.findOwnerOfSlot(one)),
                                    ElementTakes.makeWeak(array, item, (now, one, owner) ->
                                            ElementTakes.replaceInArray(now, at, one, standIn,
                                                    this.getRemovalsOf(owner))), item,
                                    TakeReach.findCalledThrough(item));
                        }
                    }
                }
                case Map<?, ?> map when map.size() <= LARGEST -> {
                    Map<Object, Object> entries = (Map<Object, Object>) map;
                    for (Map.Entry<Object, Object> entry : Wrappers.copyEntries(entries)) {
                        Object key = entry.getKey();
                        Object element = entry.getValue();
                        String mod = this.owners.findOwnerOfEntry(key, element, this.inSmall);

                        if (mod != null && element != null && this.isOwnPlain(mod, element)
                                && (key == null || ValueOwners.isPlain(key) || this.isOwnPlain(mod, key))) {
                            this.report.countOwnLeft();
                            continue;
                        }
                        Object wrapped = mod == null || element == null ? null
                                : ElementTakes.findWrappedValue(element, mod);
                        if (mod != null && wrapped != null && key != null) {
                            Object was = element;
                            this.takeOrNote(mod, this.candidates.findForEntry(key, element, this.inSmall,
                                    this.owners.getNames()), ElementTakes.askWeakEntry(key, was, (oneKey, one) ->
                                            this.owners.findOwnerOfEntry(oneKey, one, this.inSmall)),
                                    ElementTakes.makeWeakEntry(entries, key, was, (now, oneKey, one, owner) ->
                                            ElementTakes.replaceInMap(now, oneKey, one, wrapped,
                                                    this.getRemovalsOf(owner))), was);
                            continue;
                        }
                        if (mod != null) {
                            this.takeOrNote(mod,
                                    this.candidates.findForEntry(key, element, this.inSmall, this.owners.getNames()),
                                    ElementTakes.askWeakEntry(key, element, (oneKey, one) ->
                                            this.owners.findOwnerOfEntry(oneKey, one, this.inSmall)),
                                    ElementTakes.makeWeakEntry(entries, key, element, (now, oneKey, one, owner) ->
                                            ElementTakes.takeFromMap(now, oneKey, one, this.getRemovalsOf(owner))),
                                    element, TakeReach.findCalledThrough(key, element));

                            if (ElementLook.isLooking() && element != null && this.visit(element)) {
                                if (element instanceof Map<?, ?> || element instanceof Collection<?>) {
                                    this.collect(element);
                                } else if (Wrappers.isOfAnotherMod(element.getClass())) {
                                    this.lookInto(element);
                                }
                            }
                        } else if (element != null && !(element instanceof Collection<?>)
                                && !(element instanceof Map<?, ?>) && !Wrappers.isOfAnotherMod(element.getClass())) {
                            this.noteWrappedInside(element, WRAPPED_BELOW);
                        } else if (element instanceof Collection<?> nested) {
                            this.collect(nested);
                            if (nested instanceof List<?> list && list.size() <= LARGEST
                                    && CollectionParts.isFixedSize(list)) {
                                FixedLists.removeFromValue(this, entries, key);
                            }
                        } else if (element instanceof Map<?, ?> && this.visit(element)) {
                            this.collect(element);
                        } else if (element != null && this.visit(element)
                                && Wrappers.isOfAnotherMod(element.getClass())) {
                            this.lookInto(element);
                        }
                    }
                }
                default -> { }
            }
            completed = true;
        } catch (UnsupportedOperationException | IllegalStateException e) {
            this.cannotChange.merge(value.getClass().getName(), 1, Integer::sum);
            completed = true;
        } finally {
            this.inSmall = outer;
            this.collecting = collectingBefore;
            if (notes != null) {
                notes.end(value, completed);
            }
        }
    }

    void noteWrappedInside(Object holder, int steps) {
        CollectionNotes notes = ElementLook.isLooking() ? this.look.getNotes() : null;
        Set<String> was = this.enter(holder);
        if (notes != null) {
            notes.begin(holder, CollectionNotes.Origin.of(this.readingStaticsOf), this.getReader());
            notes.markWrapped(holder, steps);
        }
        try {
            this.noteWrappedFields(holder, steps);
        } finally {
            if (notes != null) {
                notes.end(holder, true);
            }
            this.leave(was);
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
            if (mod == null && steps > 1 && !Wrappers.isOfAnotherMod(value.getClass()) && this.visit(value)) {
                this.noteWrappedInside(value, steps - 1);
                continue;
            }
            if (mod == null || !this.owners.isAmong(mod)) {
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
            this.takeOrNote(owner, this.candidates.withOwner(Set.of(), owner), () -> {
                try {
                    Object now = weak.get();
                    return now != null && field.get(now) == mods ? owner : null;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return null;
                }
            }, ElementTakes.makeWeak(holder, (now, taker) -> {
                try {
                    field.set(now, inner);
                    this.getRemovalsOf(taker).add(new Removal.InField(now, field, mods, inner));
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
                    && this.visit(captured)) {
                this.lookInto(captured);
            }
        }
    }
}
