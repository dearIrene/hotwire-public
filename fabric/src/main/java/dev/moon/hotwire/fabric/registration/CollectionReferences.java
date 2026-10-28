package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.registration.ModNames;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class CollectionReferences {
    private static final Logger LOGGER = LoggerFactory.getLogger(CollectionReferences.class);

    private static final Set<String> UNREADABLE = ConcurrentHashMap.newKeySet();

    private static final int LARGEST = 100_000;

    private static final int CHANGED_READS = 20;

    private static final int WRAPPERS_IN = 512;

    private static final long SLOW_MILLIS = 100;
    private static final int SLOWEST_NAMED = 8;

    private final Map<String, List<Removal>> removals = new HashMap<>();

    private Set<Object> registered = Set.of();
    private final StaticRecomputer.Writer writer;

    private Set<Object> seen = Set.of();
    private List<String> mods = List.of();

    private Set<String> modSet = Set.of();

    private ModNames names = new ModNames(List.of());
    private Map<String, List<Removal>> mine = Map.of();

    private final Map<String, Integer> notedBy = new HashMap<>();

    private @Nullable String readingStaticsOf;
    private int ownLeft;

    private int filed;
    private int unfiled;

    private Wrappers wrappers = new Wrappers();

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
                ElementTakes.runAll(ahead, mods);
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
                if (!out.isEmpty()) {
                    this.removals.computeIfAbsent(mod, any -> new ArrayList<>()).addAll(out);
                    LOGGER.info("[Hotwire] {} of {}'s objects were taken out of collections others keep: {}",
                            out.size(), mod, out.stream().map(Removal::describe).distinct().toList());
                }
            });

            this.seen = Set.of();
            this.registered = Set.of();
            this.mods = List.of();
            this.modSet = Set.of();
            this.names = new ModNames(List.of());
            this.mine = Map.of();
            this.wrappers = new Wrappers();
        }
    }

    private void begin(List<String> mods) {
        this.seen = Collections.newSetFromMap(new IdentityHashMap<>());
        this.registered = RegisteredContent.find();
        this.mods = List.copyOf(mods);
        this.modSet = Set.copyOf(mods);
        this.names = new ModNames(mods);
        Map<String, List<Removal>> mine = new LinkedHashMap<>();
        mods.forEach(mod -> mine.put(mod, new ArrayList<>()));
        this.mine = mine;
    }

    private void walk(List<String> mods, Collection<Object> holders, Collection<Class<?>> statics,
            HandedSearch handed) {
        long began = System.nanoTime();
        this.begin(mods);

        PriorityQueue<Map.Entry<Long, String>> slowest = new PriorityQueue<>(Map.Entry.comparingByKey());

        this.ownLeft = 0;
        long holdersBegan = System.nanoTime();
        for (Object holder : holders) {
            ElementLook.stopIfAsked();
            if (this.findOwnerAmong(holder) == null) {
                this.lookInto(holder);
            }
        }
        slowest.add(Map.entry(System.nanoTime() - holdersBegan, holders.size() + " game objects"));
        long handedBegan = System.nanoTime();
        for (Object holder : handed.findHolders()) {
            if (this.seen.add(holder)) {
                this.collect(holder);
            }
        }
        slowest.add(Map.entry(System.nanoTime() - handedBegan, "what was handed to " + handed.consumers()));
        List<Class<?>> ordered = new ArrayList<>(statics);
        ordered.sort(Comparator.comparing(owner -> GameRegistrations.findModOf(owner) != null));
        for (Class<?> owner : ordered) {
            ElementLook.stopIfAsked();
            long ownerBegan = System.nanoTime();
            boolean game = owner.getName().startsWith("net.minecraft.") || owner.getName().startsWith("com.mojang.");
            this.readingStaticsOf = GameRegistrations.findModOf(owner);
            for (FieldSlots.Slot field : Fields.getReferenceFields(owner, true)) {
                Object value = CollectionParts.read(field, null);
                if (value != null && this.seen.add(value)) {
                    if (game && value instanceof Map<?, ?> map && map.size() <= LARGEST) {
                        this.readChanging(map, () -> this.removeFromNamespaced(map));
                    }
                    this.collect(value);
                    if (value instanceof List<?> list && list.size() <= LARGEST && CollectionParts.isFixedSize(list)
                            && field.getType().isAssignableFrom(List.class)) {
                        this.removeFromFixedList(field, null);
                    }
                    if (!(value instanceof Collection<?>) && !(value instanceof Map<?, ?>)
                            && !(value instanceof Object[]) && this.findOwnerAmong(value) == null) {
                        this.lookInto(value);
                    }
                }
            }
            slowest.add(Map.entry(System.nanoTime() - ownerBegan, owner.getName()));
            if (slowest.size() > SLOWEST_NAMED) {
                slowest.poll();
            }
        }
        this.readingStaticsOf = null;
        if (ElementLook.isLooking() && !this.notedBy.isEmpty()) {
            List<String> most = this.notedBy.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(SLOWEST_NAMED)
                    .map(entry -> entry.getKey() + " " + entry.getValue()).toList();
            LOGGER.info("[Hotwire] the look noted {} takes, most of them of {}", this.notedBy.values().stream()
                    .mapToInt(Integer::intValue).sum(), most);
            LOGGER.info("[Hotwire] {} of each mod's own objects in its own lists were left where they are; {} takes"
                    + " were filed under the mods they could be, {} any hold asks", this.ownLeft, this.filed,
                    this.unfiled);
            this.filed = 0;
            this.unfiled = 0;
            this.notedBy.clear();
            List<String> widest = this.candidates.takeWidest(SLOWEST_NAMED);
            if (!widest.isEmpty()) {
                LOGGER.info("[Hotwire] takes filed under 50 mods or more, by their element's class: {}", widest);
            }
        }
        long millis = (System.nanoTime() - began) / 1_000_000;
        if (millis >= SLOW_MILLIS) {
            List<String> named = new ArrayList<>();
            slowest.stream().sorted(Map.Entry.<Long, String>comparingByKey().reversed())
                    .forEach(one -> named.add(one.getValue() + " " + one.getKey() / 1_000_000 + " ms"));
            LOGGER.info("[Hotwire] {} classes' statics looked through for {} in {} ms; {}; slowest {}", statics.size(),
                    mods, millis, this.wrappers.describe(), named);
        }
    }

    private static boolean isOfOffMod(Object value) {
        String mod = GameRegistrations.findModOf(value.getClass());
        return mod != null && Tripwire.isWatched(mod);
    }

    private @Nullable String findOwnerAmong(Object value) {
        String own = this.findOwnOwner(value);
        if (own != null) {
            return own;
        }

        String named = ReflectedOwners.findOwnerOf(value instanceof Class<?> type ? type : value.getClass());
        return named != null && this.modSet.contains(named) ? named : null;
    }

    private @Nullable String findOwnOwner(Object value) {
        Class<?> type = value instanceof Class<?> named ? named : value.getClass();
        String mod = GameRegistrations.findModOf(type);
        if (mod != null && this.modSet.contains(mod)) {
            return mod;
        }
        String lambda = type.isHidden() ? ValueOwners.findLambdaOwner(type) : null;
        return lambda != null && this.modSet.contains(lambda) ? lambda : null;
    }

    private @Nullable String findOwnerOf(Object item) {
        if (this.registered.contains(item)) {
            return null;
        }
        String owner = this.findOwnerAmong(item);
        if (owner != null) {
            return owner;
        }

        String whole = !Fields.getRecordParts(item.getClass()).isEmpty() ? Wrappers.findWhollyOwner(item)
                : ValueOwners.isLambda(item) ? ValueOwners.findForwardedTo(item) : null;
        if (whole != null && this.modSet.contains(whole)) {
            return whole;
        }
        return this.inSmall ? this.wrappers.find(item, this.mods) : null;
    }

    private @Nullable String findOwnerOfEntry(@Nullable Object key, @Nullable Object element) {
        if ((key != null && this.registered.contains(key)) || (element != null && this.registered.contains(element))) {
            return null;
        }
        if (this.isStayingModsType(key)) {
            return element == null ? null : this.findOwnOwner(element);
        }
        String mod = key == null ? null : this.findOwnerAmong(key);
        if (mod == null && element != null) {
            mod = this.findOwnerOf(element);
        }
        return mod != null ? mod : Wrappers.findNamed(key, element, this.names);
    }

    private boolean isStayingModsType(@Nullable Object key) {
        String mod = key instanceof Class<?> type ? GameRegistrations.findModOf(type) : null;
        return mod != null && !this.modSet.contains(mod);
    }

    private @Nullable String findWholeOwnerOfSlot(Object item) {
        String whole = this.findOwnerAmong(item);
        if (whole == null && ValueOwners.isLambda(item)) {
            whole = this.mods.stream().filter(mod -> ValueOwners.forwardsTo(mod, item)).findFirst().orElse(null);
        }
        return whole;
    }

    private @Nullable String findOwnerOfSlot(Object item) {
        String whole = this.findWholeOwnerOfSlot(item);
        return whole != null ? whole : this.findPartsOwnerOfSlot(item);
    }

    private @Nullable String findPartsOwnerOfSlot(Object item) {
        return Fields.getRecordParts(item.getClass()).isEmpty() ? null
                : this.mods.stream().filter(mod -> Wrappers.isWhollyOf(mod, item)).findFirst().orElse(null);
    }

    private void removeFromFixedList(FieldSlots.Slot field, @Nullable Object holder) {
        if (!(CollectionParts.read(field, holder) instanceof List<?> now)) {
            return;
        }
        this.inSmall = now.size() <= WRAPPERS_IN;
        List<Object> retained = new ArrayList<>();
        Map<String, List<Removal>> out = new LinkedHashMap<>();
        for (int index = 0; index < now.size(); index++) {
            Object item = now.get(index);
            String mod = item == null ? null : this.findOwnerOf(item);
            if (mod != null) {
                out.computeIfAbsent(mod, any -> new ArrayList<>())
                        .add(new Removal.InFixedList(field, holder, index, item, this.writer));
            } else {
                retained.add(item);
            }
        }

        if (retained.isEmpty() && now.stream().allMatch(item -> item instanceof Enum<?>)) {
            return;
        }
        if (!out.isEmpty() && !this.look.hasNoted(() -> this.removeFromFixedList(field, holder))) {
            try {
                this.writer.write(field, holder, Collections.unmodifiableList(retained));
                out.forEach((mod, removed) -> this.getRemovalsOf(mod).addAll(removed));
            } catch (IllegalAccessException e) {
                LOGGER.warn("[Hotwire] {} keeps {}'s elements: {}", field, out.keySet(), e.toString());
            }
        }
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
        if (candidates == null) {
            this.unfiled++;
        } else {
            this.filed++;
        }
        if (!ElementLook.isLooking() || !this.look.hasNoted(new ElementTakes.NotedTake(whose, take, candidates))) {
            take.accept(owner);
        } else {
            this.notedBy.merge(owner, 1, Integer::sum);
        }
    }

    private boolean isOwnPlain(String mod, Object item) {
        return !this.inSmall && mod.equals(this.readingStaticsOf) && Fields.getRecordParts(item.getClass()).isEmpty()
                && !ValueOwners.isLambda(item) && GameRegistrations.findModOf(item.getClass()) != null
                && mod.equals(GameRegistrations.findModOf(item.getClass()));
    }

    private List<Removal> getRemovalsOf(String mod) {
        return this.mine.computeIfAbsent(mod, any -> new ArrayList<>());
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
        for (FieldSlots.Slot field : Fields.getReferenceFields(holder.getClass(), false)) {
            Object value = CollectionParts.read(field, holder);
            if ((value instanceof Collection<?> || value instanceof Map<?, ?> || value instanceof Object[])
                    && this.seen.add(value)) {
                this.collect(value);

                if (value instanceof List<?> list && list.size() <= LARGEST && CollectionParts.isFixedSize(list)
                        && field.getType().isAssignableFrom(List.class)) {
                    this.removeFromFixedList(field, holder);
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
        if (this.findOwnerAmong(value) != null || isOfOffMod(value)) {
            return;
        }
        this.readChanging(value, () -> this.collectOnce(value));
    }

    private void readChanging(Object value, Runnable read) {
        for (int attempt = 1; ; attempt++) {
            try {
                read.run();
                return;
            } catch (LinkageError e) {
                if (UNREADABLE.add(value.getClass().getName())) {
                    LOGGER.info("[Hotwire] {} cannot be read ({}); the takeout passes it by",
                            value.getClass().getName(), e.toString());
                }
                return;
            } catch (ConcurrentModificationException | IndexOutOfBoundsException | NoSuchElementException e) {
                if (this.look.hasNoted(() -> this.readChanging(value, read))) {
                    return;
                }
                if (attempt == CHANGED_READS) {
                    LOGGER.warn("[Hotwire] {} changed under the takeout {} times; what it holds of {} stays",
                            value.getClass().getName(), CHANGED_READS, this.mods);
                    return;
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void collectOnce(Object value) {
        boolean outer = this.inSmall;
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
                        String mod = item == null ? null : this.findOwnerOf(item);
                        if (mod != null && this.isOwnPlain(mod, item)) {
                            this.ownLeft++;
                        } else if (mod != null) {
                            this.takeOrNote(mod, this.candidates.find(item, this.inSmall),
                                    () -> this.findOwnerOf(item),
                                    owner -> ElementTakes.takeFromList(items, item, this.getRemovalsOf(owner)));
                        } else if (item != null) {
                            this.followLambda(item);
                        }
                    }
                }
                case Collection<?> collection when collection.size() <= LARGEST -> {
                    Collection<Object> items = (Collection<Object>) collection;
                    for (Object item : new ArrayList<>(items)) {
                        String mod = item == null ? null : this.findOwnerOf(item);
                        if (mod != null && this.isOwnPlain(mod, item)) {
                            this.ownLeft++;
                        } else if (mod != null) {
                            this.takeOrNote(mod, this.candidates.find(item, this.inSmall),
                                    () -> this.findOwnerOf(item),
                                    owner -> ElementTakes.takeFromCollection(items, item, this.getRemovalsOf(owner)));
                        }
                    }
                }
                case Object[] array when array.length <= LARGEST -> {
                    Class<?> slot = array.getClass().getComponentType();
                    for (int index = 0; index < array.length; index++) {
                        Object item = array[index];
                        String whole = item == null ? null : this.findWholeOwnerOfSlot(item);
                        String parts = item == null || whole != null ? null : this.findPartsOwnerOfSlot(item);
                        Object standIn = whole != null ? (StandIns.fits(slot) ? StandIns.of(slot) : null)
                                : parts != null ? CollectionParts.withStandIns(parts, Objects.requireNonNull(item))
                                : null;
                        if (item != null && standIn != null) {
                            String found = whole != null ? whole : Objects.requireNonNull(parts);
                            int at = index;
                            this.takeOrNote(found, this.candidates.find(item, true), () -> this.findOwnerOfSlot(item),
                                    owner -> ElementTakes.replaceInArray(array, at, item, standIn,
                                            this.getRemovalsOf(owner)));
                        }
                    }
                }
                case Map<?, ?> map when map.size() <= LARGEST -> {
                    Map<Object, Object> entries = (Map<Object, Object>) map;
                    for (Map.Entry<Object, Object> entry : Wrappers.copyEntries(entries)) {
                        Object key = entry.getKey();
                        Object element = entry.getValue();
                        String mod = this.findOwnerOfEntry(key, element);
                        if (mod != null) {
                            this.takeOrNote(mod,
                                    this.candidates.findForEntry(key, element, this.inSmall, this.names),
                                    () -> this.findOwnerOfEntry(key, element), owner -> ElementTakes
                                            .takeFromMap(entries, key, element, this.getRemovalsOf(owner)));

                            if (ElementLook.isLooking() && element instanceof Map<?, ?> && this.seen.add(element)) {
                                this.collect(element);
                            }
                        } else if (element instanceof Collection<?> nested) {
                            this.collect(nested);
                        } else if (element instanceof Map<?, ?> && this.seen.add(element)) {
                            this.collect(element);
                        } else if (element != null && this.seen.add(element)
                                && Wrappers.isOfAnotherMod(element.getClass())) {
                            this.lookInto(element);
                        }
                    }
                }
                default -> {
                }
            }
        } catch (UnsupportedOperationException | IllegalStateException e) {
            LOGGER.info("[Hotwire] {} keeps {}'s objects: it cannot change", value.getClass().getName(), this.mods);
        } finally {
            this.inSmall = outer;
        }
    }

    @SuppressWarnings("unchecked")
    private void removeFromNamespaced(Map<?, ?> map) {
        Map<Object, Object> entries = (Map<Object, Object>) map;
        try {
            if (!this.hasNamespacedKey(entries)) {
                return;
            }
            for (Map.Entry<Object, Object> entry : Wrappers.copyEntries(entries)) {
                String mod = this.registered.contains(entry.getKey()) || this.registered.contains(entry.getValue())
                        ? null : this.findModNamespaceOf(entry.getKey(), entry.getValue());
                if (mod != null && !this.look.hasNoted(() -> this.removeFromNamespaced(map))
                        && entries.remove(entry.getKey(), entry.getValue())) {
                    this.getRemovalsOf(mod).add(new Removal.InMap(entries, entry.getKey(), entry.getValue()));
                }
            }
        } catch (UnsupportedOperationException | IllegalStateException e) {
            LOGGER.info("[Hotwire] {} keeps {}'s entries: it cannot change", map.getClass().getName(), this.mods);
        }
    }

    private boolean hasNamespacedKey(Map<Object, Object> entries) {
        try {
            for (Map.Entry<Object, Object> entry : Wrappers.copyEntries(entries)) {
                if (this.findModNamespaceOf(entry.getKey(), entry.getValue()) != null) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private @Nullable String findModNamespaceOf(@Nullable Object key, @Nullable Object value) {
        String named = CollectionParts.findNamespaceOf(key);
        if (named == null || this.modSet.contains(named)) {
            return named;
        }
        if (value == null || value instanceof Identifier) {
            return null;
        }
        for (FieldSlots.Slot field : Fields.getReferenceFields(value.getClass(), false)) {
            try {
                String inner = CollectionParts.findNamespaceOf(field.get(value));
                if (inner != null && this.modSet.contains(inner)) {
                    return inner;
                }
            } catch (IllegalAccessException e) {
            }
        }
        return null;
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
