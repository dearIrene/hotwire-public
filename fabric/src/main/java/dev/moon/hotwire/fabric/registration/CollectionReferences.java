package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private static final long SLOW_MILLIS = 100;

    private final Map<String, List<Removal>> removals = new HashMap<>();
    private final StaticRecomputer.Writer writer;

    private Set<Object> seen = Set.of();
    private List<String> mods = List.of();

    private ElementOwners owners = new ElementOwners(List.of(), Set.of());
    private Map<String, List<Removal>> mine = Map.of();

    private @Nullable String readingStaticsOf;

    private @Nullable Object collecting;

    private WalkReport report = new WalkReport();

    private boolean inSmall;

    private final TakeCandidates candidates = new TakeCandidates();

    final ElementLook look = new ElementLook();

    CollectionReferences(StaticRecomputer.Writer writer) {
        this.writer = writer;
    }

    synchronized void takeOut(List<String> mods, Collection<Object> holders, Collection<Class<?>> statics,
            HandedSearch handed) {
        List<Runnable> ahead = this.look.open(mods);
        try {
            if (ahead == null) {
                this.walk(mods, holders, statics, handed);
                this.look.close();
            } else {
                this.begin(mods);
                long began = System.nanoTime();
                ElementTakes.runAll(ahead, mods, this.look.getContainersOfTakes(),
                        container -> String.valueOf(this.look.getNotes().findOrigin(container).staticsOf()));
                long millis = (System.nanoTime() - began) / 1_000_000;
                if (millis >= SLOW_MILLIS) {
                    LOGGER.info("[Hotwire] {} takes the look noted were run for {} in {} ms", ahead.size(), mods,
                            millis);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            this.look.drop();
            throw e;
        } finally {
            this.mine.forEach((mod, out) -> {
                out.forEach(Removal::resetHolder);
                if (!out.isEmpty()) {
                    this.removals.computeIfAbsent(mod, any -> new ArrayList<>()).addAll(out);
                    LOGGER.info("[Hotwire] {} of {}'s objects were taken out of collections others keep: {}",
                            out.size(), mod, out.stream().map(Removal::describe).distinct().toList());
                }
            });

            this.seen = Set.of();
            this.mods = List.of();
            this.owners = new ElementOwners(List.of(), Set.of());
            this.mine = Map.of();
        }
    }

    private static final int WRAPPED_BELOW = 3;

    private void noteWrappedInside(Object holder, int steps) {
        CollectionNotes notes = ElementLook.isLooking() ? this.look.getNotes() : null;
        if (notes != null) {
            notes.begin(holder, new CollectionNotes.Origin(this.readingStaticsOf, false, null));
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
    private void noteWrappedFields(Object holder, int steps) {
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
            if (mod == null && steps > 1 && !Wrappers.isOfAnotherMod(value.getClass()) && this.seen.add(value)) {
                this.noteWrappedInside(value, steps - 1);
                continue;
            }
            if (mod == null || !this.mods.contains(mod)) {
                continue;
            }

            Object inner = ElementTakes.findWrappedValue(value, mod);
            if (inner == null || !field.getType().isInstance(inner)) {
                continue;
            }
            Object mods = value;
            String owner = mod;
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
            }));
        }
    }

    private void readStatic(FieldSlots.Slot field, Object value, boolean game) {
        CollectionNotes notes = ElementLook.isLooking() ? this.look.getNotes() : null;
        if (notes != null) {
            notes.begin(value, new CollectionNotes.Origin(this.readingStaticsOf, game, field));
        }
        boolean completed = false;
        try {
            if (game && value instanceof Map<?, ?> map && map.size() <= LARGEST) {
                this.readChanging(map, () -> NamespacedMaps.removeFrom(this, map));
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
            if (notes != null) {
                notes.end(value, completed);
            }
        }
    }

    private void begin(List<String> mods) {
        this.seen = Collections.newSetFromMap(new IdentityHashMap<>());
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
        boolean every = notes != null && notes.isForEvery();
        long holdersBegan = System.nanoTime();
        if (notes != null) {
            notes.setInWorld(true);
        }
        for (Object holder : holders) {
            ElementLook.stopIfAsked();
            if (this.owners.findOwnerAmong(holder) == null) {
                this.lookInto(holder);
            }
        }
        this.report.time(holdersBegan, holders.size() + " game objects");
        long handedBegan = System.nanoTime();
        for (Object holder : handed.findHolders()) {
            if (this.seen.add(holder)) {
                this.collect(holder);
            }
        }
        if (notes != null) {
            notes.setInWorld(false);
        }
        this.report.time(handedBegan, "what was handed to " + handed.consumers());
        List<Class<?>> ordered = new ArrayList<>(statics);
        ordered.sort(Comparator.comparing(owner -> GameRegistrations.findModOf(owner) != null));
        int skipped = 0;
        for (Class<?> owner : ordered) {
            ElementLook.stopIfAsked();

            if (notes != null && every && notes.hasReadStatics(owner)) {
                skipped++;
                continue;
            }
            long ownerBegan = System.nanoTime();
            boolean game = owner.getName().startsWith("net.minecraft.") || owner.getName().startsWith("com.mojang.");
            this.readingStaticsOf = GameRegistrations.findModOf(owner);
            for (FieldSlots.Slot field : Fields.getReferenceFields(owner, true)) {
                Object value = CollectionParts.read(field, null);
                if (value != null && this.seen.add(value)) {
                    this.readStatic(field, value, game);
                }
            }
            if (notes != null) {
                notes.markStaticsRead(owner);
            }
            this.report.time(ownerBegan, owner.getName());
        }

        boolean narrow = notes != null && !every && ElementLook.hasLookedForEvery();
        if (notes != null && (every || narrow)) {
            long againBegan = System.nanoTime();
            int again = 0;
            for (Object container : every ? notes.findContainers() : notes.findContainersUnder(mods)) {
                ElementLook.stopIfAsked();
                if (!this.seen.add(container)) {
                    continue;
                }
                again++;
                CollectionNotes.Origin origin = notes.findOrigin(container);
                this.readingStaticsOf = origin.staticsOf();
                if (origin.field() != null) {
                    this.readStatic(origin.field(), container, origin.game());
                } else {
                    this.collect(container);
                }
            }
            LOGGER.info("[Hotwire] the look read {} collections of the notes again in {} ms, and passed the statics"
                    + " of {} classes read before", again, (System.nanoTime() - againBegan) / 1_000_000, skipped);
        }
        this.readingStaticsOf = null;
        this.report.end(ElementLook.isLooking(), mods, statics.size(), began, this.owners.getWrappers().describe(),
                this.candidates);
    }

    private void takeOrNote(String owner, @Nullable Set<String> candidates, Supplier<@Nullable String> asked,
            Consumer<String> take) {
        boolean small = this.inSmall;
        Supplier<@Nullable String> whose = () -> {
            boolean was = this.inSmall;
            this.inSmall = small;
            try {
                return asked.get();
            } finally {
                this.inSmall = was;
            }
        };
        this.report.count(candidates != null);
        if (!ElementLook.isLooking() || !this.look.hasNoted(new ElementTakes.NotedTake(whose, take,
                this.candidates.withOwner(candidates, owner)))) {
            take.accept(owner);
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
        for (Map.Entry<String, Removal> one : back) {
            try {
                one.getValue().restore();
            } catch (RuntimeException e) {
                LOGGER.warn("[Hotwire] one of {}'s objects could not go back into {}", one.getKey(),
                        one.getValue().describe(), e);
            }
        }
    }

    private void lookInto(Object holder) {
        if (holder instanceof Registry<?>) {
            return;
        }

        if (Proxy.isProxyClass(holder.getClass())) {
            Object handler = Proxy.getInvocationHandler(holder);
            if (this.seen.add(handler)) {
                this.lookInto(handler);
            }
            return;
        }
        for (FieldSlots.Slot field : Fields.getReferenceFields(holder.getClass(), false)) {
            Object value = CollectionParts.read(field, holder);
            if ((value instanceof Collection<?> || value instanceof Map<?, ?> || value instanceof Object[])
                    && this.seen.add(value)) {
                LazyFields.noteHolder(value, holder);
                this.collect(value);

                if (value instanceof List<?> list && list.size() <= LARGEST && CollectionParts.isFixedSize(list)
                        && field.getType().isAssignableFrom(List.class)) {
                    FixedLists.removeFromField(this, field, holder);
                }
            } else if (value != null && Wrappers.isLookedInto(value) && this.seen.add(value)) {
                for (FieldSlots.Slot inner : Fields.getReferenceFields(value.getClass(), false)) {
                    Object innerValue = CollectionParts.read(inner, value);
                    if ((innerValue instanceof Collection<?> || innerValue instanceof Map<?, ?>
                            || innerValue instanceof Object[]) && this.seen.add(innerValue)) {
                        this.collect(innerValue);
                    }
                }
            }
        }
    }

    private void collect(Object value) {
        ElementLook.stopIfAsked();
        if (this.owners.findOwnerAmong(value) != null || GameRegistrations.isOfOffMod(value.getClass())) {
            return;
        }
        this.readChanging(value, () -> this.collectOnce(value));
    }

    private void readChanging(Object value, Runnable read) {
        CollectionParts.readChanging(value, read, this.look, this.mods);
    }

    @SuppressWarnings("unchecked")
    private void collectOnce(Object value) {
        boolean outer = this.inSmall;
        CollectionNotes notes = ElementLook.isLooking() ? this.look.getNotes() : null;
        if (notes != null) {
            notes.begin(value, new CollectionNotes.Origin(this.readingStaticsOf, false, null));
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
                    for (int index = items.size() - 1; index >= 0; index--) {
                        Object item = items.get(index);
                        String mod = item == null ? null : this.owners.findOwnerOf(item, this.inSmall);
                        if (mod != null && this.isOwnPlain(mod, item)) {
                            this.report.countOwnLeft();
                        } else if (mod != null) {
                            this.takeOrNote(mod, this.candidates.find(item, this.inSmall),
                                    () -> this.owners.findOwnerOf(item, this.inSmall),
                                    ElementTakes.makeWeak(items, (now, owner) ->
                                            ElementTakes.takeFromList(now, item, this.getRemovalsOf(owner))));
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
                    for (Object item : new ArrayList<>(items)) {
                        String mod = item == null ? null : this.owners.findOwnerOf(item, this.inSmall);
                        if (mod != null && this.isOwnPlain(mod, item)) {
                            this.report.countOwnLeft();
                        } else if (mod != null) {
                            this.takeOrNote(mod, this.candidates.find(item, this.inSmall),
                                    () -> this.owners.findOwnerOf(item, this.inSmall),
                                    ElementTakes.makeWeak(items, (now, owner) ->
                                            ElementTakes.takeFromCollection(now, item, this.getRemovalsOf(owner))));
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
                                    () -> this.owners.findOwnerOfSlot(item),
                                    ElementTakes.makeWeak(array, (now, owner) -> ElementTakes.replaceInArray(now,
                                            at, item, standIn, this.getRemovalsOf(owner))));
                        }
                    }
                }
                case Map<?, ?> map when map.size() <= LARGEST -> {
                    Map<Object, Object> entries = (Map<Object, Object>) map;
                    int takesBefore = this.report.countTakes();
                    boolean handlers = false;
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
                                    this.owners.getNames()), () -> this.owners.findOwnerOfEntry(key, was, this.inSmall),
                                    ElementTakes.makeWeak(entries, (now, owner) -> ElementTakes.replaceInMap(now, key,
                                            was, wrapped, this.getRemovalsOf(owner))));
                            continue;
                        }
                        if (mod != null) {
                            this.takeOrNote(mod,
                                    this.candidates.findForEntry(key, element, this.inSmall, this.owners.getNames()),
                                    () -> this.owners.findOwnerOfEntry(key, element, this.inSmall),
                                    ElementTakes.makeWeak(entries, (now, owner) -> ElementTakes.takeFromMap(now, key,
                                            element, this.getRemovalsOf(owner))));

                            boolean holds = element instanceof Map<?, ?> || element instanceof Collection<?>;
                            if (ElementLook.isLooking() && holds && this.seen.add(element)) {
                                this.collect(element);
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
                        } else if (element instanceof Map<?, ?> && this.seen.add(element)) {
                            this.collect(element);
                        } else if (element != null && this.seen.add(element)
                                && Wrappers.isOfAnotherMod(element.getClass())) {
                            this.lookInto(element);
                            handlers = true;
                        }
                    }

                    if (handlers && ElementLook.isLooking() && this.report.countTakes() > takesBefore) {
                        this.look.hasNoted(new ElementTakes.NotedTake(() -> "", any -> this.collect(map), null));
                    }
                }
                default -> {
                }
            }
            completed = true;
        } catch (UnsupportedOperationException | IllegalStateException e) {
            LOGGER.info("[Hotwire] {} keeps {}'s objects: it cannot change", value.getClass().getName(), this.mods);
            completed = true;
        } finally {
            this.inSmall = outer;
            this.collecting = collectingBefore;
            if (notes != null) {
                notes.end(value, completed);
            }
        }
    }

    private void followLambda(Object item) {
        Class<?> type = item.getClass();
        if (!type.isHidden() || !type.getName().contains("$$Lambda")) {
            return;
        }
        for (FieldSlots.Slot field : Fields.getReferenceFields(type, false)) {
            Object captured = CollectionParts.read(field, item);
            if (captured != null && captured.getClass().getName().startsWith("net.minecraft.")
                    && this.seen.add(captured)) {
                this.lookInto(captured);
            }
        }
    }
}
