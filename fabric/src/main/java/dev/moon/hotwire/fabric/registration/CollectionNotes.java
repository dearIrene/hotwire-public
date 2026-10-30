package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

final class CollectionNotes {
    private static final Object NOWHERE = new Object();

    private record Frame(Object container, List<Runnable> before, List<Runnable> now, boolean fresh) {
    }

    record Origin(@Nullable String staticsOf, boolean game, FieldSlots.@Nullable Slot field) {
    }

    private final IdentityHashMap<Object, List<Runnable>> takes = new IdentityHashMap<>();

    private final List<Object> order = new ArrayList<>();
    private final Set<Object> ofWorld = Collections.newSetFromMap(new IdentityHashMap<>());
    private final IdentityHashMap<Object, Origin> origins = new IdentityHashMap<>();
    private final Set<Class<?>> staticsRead = Collections.newSetFromMap(new IdentityHashMap<>());

    private @Nullable Collection<String> lookingFor;
    private boolean every;

    private List<Object> toRead = List.of();
    private final Deque<Frame> open = new ArrayDeque<>();

    private boolean inWorld;

    synchronized void startLook(@Nullable Collection<String> mods) {
        this.lookingFor = mods == null ? null : List.copyOf(mods);
        this.every = mods == null;
        this.open.clear();
        if (this.every) {
            this.ofWorld.forEach(this::remove);
            this.ofWorld.clear();
            this.order.removeIf(container -> !this.takes.containsKey(container));
            this.takes.remove(NOWHERE);
        }
        this.toRead = this.every ? this.listContainers() : List.of();
    }

    synchronized boolean isForEvery() {
        return this.every;
    }

    synchronized void dropLook() {
        while (!this.open.isEmpty()) {
            Frame frame = this.open.pop();
            this.takes.put(frame.container(), frame.before());
        }
    }

    synchronized void setInWorld(boolean inWorld) {
        this.inWorld = inWorld;
    }

    @SuppressWarnings("ReferenceEquality")
    synchronized void begin(Object container, Origin origin) {
        Frame outer = this.open.peek();
        if (outer != null && outer.container() == container) {
            this.open.push(outer);
            return;
        }
        List<Runnable> before = this.takes.getOrDefault(container, List.of());
        List<Runnable> left = new ArrayList<>();
        if (this.lookingFor != null) {
            for (Runnable take : before) {
                if (take instanceof ElementTakes.NotedTake filed && filed.candidates() != null
                        && Collections.disjoint(filed.candidates(), this.lookingFor)) {
                    left.add(take);
                }
            }
        }
        boolean fresh = !this.takes.containsKey(container);
        this.takes.put(container, left);
        this.origins.put(container, origin);
        if (this.inWorld) {
            this.ofWorld.add(container);
        }
        this.open.push(new Frame(container, before, left, fresh));
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
        if (!completed) {
            this.takes.put(container, frame.before());
        } else if (frame.now().isEmpty() && !(container instanceof Collection<?>) && !(container instanceof Map<?, ?>)
                && !(container instanceof Object[])) {
            this.remove(container);
            return;
        }
        if (frame.fresh() && this.takes.containsKey(container)) {
            this.order.add(container);
        }
    }

    synchronized void note(Runnable take) {
        Frame frame = this.open.peek();
        if (frame != null) {
            frame.now().add(take);
            return;
        }
        this.takes.computeIfAbsent(NOWHERE, any -> new ArrayList<>()).add(take);
    }

    synchronized List<Runnable> findTakesFor(Collection<String> mods, boolean all) {
        List<Runnable> found = new ArrayList<>();
        for (Object container : this.order) {
            addFor(found, this.takes.getOrDefault(container, List.of()), mods, all);
        }
        addFor(found, this.takes.getOrDefault(NOWHERE, List.of()), mods, all);
        return found;
    }

    private static void addFor(List<Runnable> found, List<Runnable> takes, Collection<String> mods, boolean all) {
        for (Runnable take : takes) {
            if (all || !(take instanceof ElementTakes.NotedTake filed) || filed.candidates() == null
                    || !Collections.disjoint(filed.candidates(), mods)) {
                found.add(take);
            }
        }
    }

    synchronized List<Object> findContainers() {
        return this.toRead;
    }

    @SuppressWarnings("ReferenceEquality")
    private List<Object> listContainers() {
        List<Object> found = new ArrayList<>();
        for (Object container : this.order) {
            if (container != NOWHERE && this.takes.containsKey(container) && !this.ofWorld.contains(container)) {
                found.add(container);
            }
        }
        return found;
    }

    synchronized Origin findOrigin(Object container) {
        return this.origins.getOrDefault(container, new Origin(null, false, null));
    }

    synchronized boolean hasReadStatics(Class<?> owner) {
        return this.staticsRead.contains(owner);
    }

    synchronized void markStaticsRead(Class<?> owner) {
        this.staticsRead.add(owner);
    }

    synchronized int countTakes() {
        return this.takes.values().stream().mapToInt(List::size).sum();
    }

    synchronized int countContainers() {
        return this.order.size();
    }

    private void remove(Object container) {
        this.takes.remove(container);
        this.origins.remove(container);
    }
}
