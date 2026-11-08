package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

final class CheckReport {
    private static final int STEPS = 14;

    static final String NOTES = "the notes";

    private @Nullable IdentityHashMap<Object, Object> reachedFrom;
    private final ArrayDeque<Object> open = new ArrayDeque<>();

    private @Nullable IdentityHashMap<Object, Object> fromLooks;

    void begin() {
        this.reachedFrom = new IdentityHashMap<>();
        this.open.clear();
    }

    boolean isOn() {
        return this.reachedFrom != null;
    }

    void enter(Object node) {
        IdentityHashMap<Object, Object> reached = this.reachedFrom;
        if (reached != null) {
            reached.putIfAbsent(node, this.open.isEmpty() ? "a root" : Objects.requireNonNull(this.open.peek()));
            this.open.push(node);
        }
    }

    void leave() {
        if (this.reachedFrom != null && !this.open.isEmpty()) {
            this.open.pop();
        }
    }

    @SuppressWarnings("ReferenceEquality")
    boolean isOnlyThroughNotes(Object container) {
        IdentityHashMap<Object, Object> reached = this.reachedFrom;
        if (reached == null) {
            return false;
        }
        Object at = reached.get(container);
        for (int step = 0; at != null && step < STEPS * 4; step++) {
            if (at == NOTES) {
                return true;
            }
            at = reached.get(at);
        }
        return false;
    }

    void endLook() {
        IdentityHashMap<Object, Object> reached = this.reachedFrom;
        IdentityHashMap<Object, Object> earlier = this.fromLooks;
        if (reached != null) {
            if (earlier == null) {
                this.fromLooks = reached;
            } else {
                reached.forEach(earlier::putIfAbsent);
            }
        }
        this.reachedFrom = null;
        this.open.clear();
    }

    void endCheck() {
        this.reachedFrom = null;
        this.fromLooks = null;
        this.open.clear();
    }

    String describePath(Object container, IdentityHashMap<Object, CollectionNotes.NoteSummary> known,
            IdentityHashMap<Object, CollectionNotes.Holder> watched) {
        IdentityHashMap<Object, Object> reached = this.reachedFrom;
        if (reached == null) {
            return "";
        }
        StringBuilder line = new StringBuilder();
        Object below = container;
        Object at = reached.get(container);
        for (int step = 0; at != null && step < STEPS; step++) {
            if (at instanceof String marker) {
                line.append(" < ").append(marker);
                break;
            }
            line.append(" < ").append(this.describeStep(at, below, known, watched));
            below = at;
            at = reached.get(at);
        }
        return line.toString();
    }

    private String describeStep(Object at, Object below, IdentityHashMap<Object, CollectionNotes.NoteSummary> known,
            IdentityHashMap<Object, CollectionNotes.Holder> watched) {
        String name = at.getClass().getName();
        if (at instanceof Collection<?> || at instanceof Map<?, ?> || at instanceof Object[]) {
            return name + (known.containsKey(at) ? " (noted)" : " (new)");
        }
        String field = name + "." + findFieldHolding(at, below);
        CollectionNotes.Holder was = watched.get(at);
        if (was != null) {
            String moved = was.print() == CollectionReferences.printFields(at) ? "" : ", moved";
            return field + " (watched" + moved + (was.through() ? ", read through)" : ", inside)");
        }
        if (Fields.getMovingFields(at.getClass(), false).isEmpty()) {
            return field + " (all final)";
        }
        return field + " (unwatched" + this.describeLooks(at) + ")";
    }

    private String describeLooks(Object at) {
        IdentityHashMap<Object, Object> earlier = this.fromLooks;
        if (earlier == null) {
            return "";
        }
        Object parent = earlier.get(at);
        if (parent == null) {
            return ", no look since the last check met it";
        }
        String where = parent instanceof String marker ? marker
                : parent.getClass().getName() + "." + findFieldHolding(parent, at);
        return ", a look since the last check met it in " + where;
    }

    static String describeKnown(CollectionNotes.@Nullable NoteSummary before) {
        return before == null ? " (new to the notes)" : " (noted with " + before.takes() + " takes under "
                + new TreeSet<>(before.under()) + ", read by " + new TreeSet<>(before.readers())
                + (before.leftByOff() ? ", left by the off as nothing on reaches it" : "") + ")";
    }

    @SuppressWarnings("ReferenceEquality")
    static String describeAnswered(ElementTakes.@Nullable Answers answered, Object container) {
        if (answered == null) {
            return "";
        }
        int count = 0;
        Set<String> owners = new TreeSet<>();
        for (int at = 0; at < answered.containers().size(); at++) {
            if (answered.containers().get(at) == container) {
                count++;
                owners.add(String.valueOf(answered.owners().get(at)));
            }
        }
        return " (the off's answers: " + count + (count == 0 ? "" : " for " + owners) + ")";
    }

    static String describeFiled(ElementTakes.NotedTake noted) {
        return " (filed now under " + (noted.candidates() == null ? "any" : new TreeSet<>(noted.candidates()))
                + ", called through " + (noted.calledThrough() == null ? "anything"
                : new TreeSet<>(noted.calledThrough())) + ")";
    }

    @SuppressWarnings("ReferenceEquality")
    private static String findFieldHolding(Object holder, Object value) {
        for (FieldSlots.Slot field : Fields.getReferenceFields(holder.getClass(), false)) {
            if (CollectionParts.read(field, holder) == value) {
                return field.getName() + (Modifier.isFinal(field.getModifiers()) ? " (final)" : "");
            }
        }
        return "?";
    }
}
