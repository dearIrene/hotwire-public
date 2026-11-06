package dev.moon.hotwire.dev;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

public final class BuildMerge {
    private BuildMerge() {
    }

    public record Refusal(Kind kind, int build) {
        public enum Kind { TOO_FEW, START, NOT_SAVED, NO_RECORD, BASE_GONE }
    }

    public record Conflict(String entry, List<Integer> builds, int winner, List<Integer> dropped) {
        public Conflict {
            builds = List.copyOf(builds);
            dropped = List.copyOf(dropped);
        }
    }

    public record BuildGap(int build, List<Integer> skipped, SortedSet<String> entries) {
        public BuildGap {
            skipped = List.copyOf(skipped);
            entries = new TreeSet<>(entries);
        }
    }

    public record Plan(List<Integer> builds, int base, int tip, Map<String, Integer> sources, Set<String> gone,
            List<Conflict> conflicts, List<BuildGap> leftOut, Set<Integer> removed, int parent, DevDelta delta,
            @Nullable Refusal refusal) {
        public Plan {
            builds = List.copyOf(builds);
            sources = Map.copyOf(sources);
            gone = Set.copyOf(gone);
            conflicts = List.copyOf(conflicts);
            leftOut = List.copyOf(leftOut);
            removed = Set.copyOf(removed);
        }

        public boolean isFast() {
            return this.tip >= 0;
        }

        private static Plan refuse(List<Integer> builds, Refusal.Kind kind, int build) {
            return new Plan(builds, -1, -1, Map.of(), Set.of(), List.of(), List.of(), Set.of(), -1,
                    new DevDelta(Set.of(), Set.of()), new Refusal(kind, build));
        }
    }

    public static Plan plan(Collection<Integer> chosen, Map<Integer, Integer> parents, Map<Integer, DevDelta> deltas,
            Set<Integer> readable, int running, Set<Integer> pinned) {
        List<Integer> builds = new ArrayList<>(new TreeSet<>(chosen));
        if (builds.size() < 2) {
            return Plan.refuse(builds, Refusal.Kind.TOO_FEW, -1);
        }
        if (builds.contains(0)) {
            return Plan.refuse(builds, Refusal.Kind.START, 0);
        }
        for (int build : builds) {
            if (!readable.contains(build)) {
                return Plan.refuse(builds, Refusal.Kind.NOT_SAVED, build);
            }
        }
        Integer found = findBase(builds, parents);
        Set<Integer> picked = new HashSet<>(builds);
        if (found == null || (!picked.contains(found) && !readable.contains(found))) {
            return Plan.refuse(builds, Refusal.Kind.BASE_GONE, found == null ? -1 : found);
        }
        int base = found;
        for (int build : builds) {
            if (build != base && !deltas.containsKey(build)) {
                return Plan.refuse(builds, Refusal.Kind.NO_RECORD, build);
            }
        }
        int newest = builds.getLast();
        boolean line = picked.contains(base) && isLine(builds, base, parents)
                && (!picked.contains(running) || running == newest);
        int tip = line ? newest : -1;

        Map<String, List<Integer>> writers = new TreeMap<>();
        for (int build : builds) {
            DevDelta delta = deltas.get(build);
            if (delta == null) {
                continue;
            }
            for (String entry : delta.put()) {
                writers.computeIfAbsent(entry, ignored -> new ArrayList<>()).add(build);
            }
            for (String entry : delta.gone()) {
                writers.computeIfAbsent(entry, ignored -> new ArrayList<>()).add(build);
            }
        }
        Map<String, Integer> sources = new TreeMap<>();
        Set<String> gone = new TreeSet<>();
        List<Conflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> entry : writers.entrySet()) {
            List<Integer> wrote = entry.getValue();
            int winner = wrote.getLast();
            if (deltas.get(winner).put().contains(entry.getKey())) {
                sources.put(entry.getKey(), winner);
            } else {
                gone.add(entry.getKey());
            }

            List<Integer> dropped = new ArrayList<>();
            for (int build : wrote) {
                if (build != winner && !isAncestor(build, winner, parents)) {
                    dropped.add(build);
                }
            }
            if (!dropped.isEmpty()) {
                conflicts.add(new Conflict(entry.getKey(), wrote, winner, dropped));
            }
        }
        List<BuildGap> leftOut = new ArrayList<>();
        for (int build : builds) {
            if (build == base) {
                continue;
            }
            List<Integer> skipped = new ArrayList<>();
            Integer up = parents.get(build);
            while (up != null && up != base && !picked.contains(up) && skipped.size() <= parents.size()) {
                skipped.add(up);
                up = parents.get(up);
            }
            if (skipped.isEmpty()) {
                continue;
            }
            SortedSet<String> missing = new TreeSet<>();
            for (int left : skipped) {
                DevDelta delta = deltas.get(left);
                if (delta != null) {
                    missing.addAll(delta.put());
                    missing.addAll(delta.gone());
                }
            }
            missing.removeAll(writers.keySet());
            leftOut.add(new BuildGap(build, skipped, missing));
        }
        Set<Integer> removed = new TreeSet<>(picked);
        removed.remove(tip);
        removed.remove(running);
        removed.removeAll(pinned);
        int parent = base;
        while (removed.contains(parent)) {
            Integer up = parents.get(parent);
            parent = up == null ? -1 : up;
        }
        return new Plan(builds, base, tip, sources, gone, conflicts, leftOut, removed, parent,
                new DevDelta(sources.keySet(), gone), null);
    }

    private static @Nullable Integer findBase(List<Integer> builds, Map<Integer, Integer> parents) {
        List<Integer> first = findLine(builds.getFirst(), parents);
        List<Set<Integer>> others = new ArrayList<>();
        for (int build : builds.subList(1, builds.size())) {
            others.add(new HashSet<>(findLine(build, parents)));
        }
        for (int candidate : first) {
            if (others.stream().allMatch(line -> line.contains(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    private static List<Integer> findLine(int build, Map<Integer, Integer> parents) {
        List<Integer> line = new ArrayList<>();
        Integer at = build;
        while (at != null && line.size() <= parents.size()) {
            line.add(at);
            at = parents.get(at);
        }
        return line;
    }

    private static boolean isAncestor(int older, int newer, Map<Integer, Integer> parents) {
        List<Integer> line = findLine(newer, parents);
        return line.subList(1, line.size()).contains(older);
    }

    private static boolean isLine(List<Integer> builds, int base, Map<Integer, Integer> parents) {
        Set<Integer> made = new HashSet<>();
        for (int build : builds) {
            if (build == base) {
                continue;
            }
            Integer parent = parents.get(build);
            if (parent == null || !builds.contains(parent) || !made.add(parent)) {
                return false;
            }
        }
        return true;
    }

    public static DevBuildInfo combine(DevBuildInfo a, DevBuildInfo b, int number, int madeOn, long at,
            List<Integer> merged, Map<String, Map<String, List<Integer>>> linesNow) {
        Map<String, DevClassChange> changed = new LinkedHashMap<>();
        Set<String> added = new TreeSet<>(a.addedClasses());
        Set<String> removed = new TreeSet<>(a.removedClasses());
        for (DevClassChange change : a.classes()) {
            changed.put(change.name(), change);
        }
        for (String name : b.addedClasses()) {
            if (removed.remove(name)) {
                changed.put(name, new DevClassChange(name, "", "", List.of(), List.of(), List.of(), Map.of()));
            } else {
                added.add(name);
            }
        }
        for (String name : b.removedClasses()) {
            changed.remove(name);
            if (!added.remove(name)) {
                removed.add(name);
            }
        }
        for (DevClassChange change : b.classes()) {
            if (added.contains(change.name())) {
                continue;
            }
            DevClassChange before = changed.get(change.name());
            changed.put(change.name(), before == null ? change : combineMethods(before, change,
                    linesNow.getOrDefault(change.type(), change.lines())));
        }
        Set<String> files = new TreeSet<>(a.files());
        files.addAll(b.files());
        Set<String> notes = new LinkedHashSet<>(a.notes());
        notes.addAll(b.notes());
        List<DevClassChange> classes = new ArrayList<>(changed.values());
        classes.sort((x, y) -> x.name().compareTo(y.name()));
        return new DevBuildInfo(number, madeOn, at, classes, List.copyOf(added), List.copyOf(removed),
                List.copyOf(files), List.copyOf(notes), merged);
    }

    private enum Was { CHANGED, ADDED, REMOVED }

    private static DevClassChange combineMethods(DevClassChange a, DevClassChange b, Map<String, List<Integer>> lines) {
        Map<String, Was> first = findWas(a);
        Map<String, Was> then = findWas(b);
        Map<String, Was> both = new TreeMap<>(first);
        then.forEach((name, now) -> {
            Was before = first.get(name);
            Was result;
            if (before == null) {
                result = now;
            } else if (before == Was.ADDED && now == Was.REMOVED) {
                result = null;
            } else if (before == Was.ADDED) {
                result = Was.ADDED;
            } else {
                result = now == Was.REMOVED ? Was.REMOVED : Was.CHANGED;
            }
            if (result == null) {
                both.remove(name);
            } else {
                both.put(name, result);
            }
        });
        List<String> changed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        Map<String, List<Integer>> spans = new HashMap<>();
        both.forEach((name, was) -> {
            switch (was) {
                case CHANGED -> changed.add(name);
                case ADDED -> added.add(name);
                case REMOVED -> removed.add(name);
            }
            List<Integer> span = lines.get(name);
            if (span != null && was != Was.REMOVED) {
                spans.put(name, span);
            }
        });
        return new DevClassChange(b.name(), b.type().isEmpty() ? a.type() : b.type(),
                b.file().isEmpty() ? a.file() : b.file(), changed, added, removed, spans);
    }

    private static Map<String, Was> findWas(DevClassChange change) {
        Map<String, Was> was = new HashMap<>();
        change.changed().forEach(name -> was.put(name, Was.CHANGED));
        change.added().forEach(name -> was.put(name, Was.ADDED));
        change.removed().forEach(name -> was.put(name, Was.REMOVED));
        return was;
    }
}
