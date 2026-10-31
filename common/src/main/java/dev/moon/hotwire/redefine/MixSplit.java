package dev.moon.hotwire.redefine;

import dev.moon.hotwire.classfile.ClassHeader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

final class MixSplit {
    private MixSplit() {
    }

    static List<Set<String>> split(Set<String> targets, Function<String, byte @Nullable []> bytesOf, int ways) {
        if (ways <= 1 || targets.size() <= 1) {
            return List.of(targets);
        }
        Map<String, String> leader = new HashMap<>();
        targets.forEach(target -> leader.put(target, target));
        Map<String, List<String>> above = new HashMap<>();
        for (String target : targets) {
            Set<String> seen = new HashSet<>();
            Deque<String> next = new ArrayDeque<>(findAbove(target, bytesOf, above));
            while (!next.isEmpty()) {
                String type = next.poll();
                if (!seen.add(type)) {
                    continue;
                }
                if (leader.containsKey(type)) {
                    join(leader, target, type);
                }
                next.addAll(findAbove(type, bytesOf, above));
            }
        }
        Map<String, List<String>> groups = new HashMap<>();
        for (String target : targets) {
            groups.computeIfAbsent(find(leader, target), key -> new ArrayList<>()).add(target);
        }
        List<List<String>> bySize = new ArrayList<>(groups.values());
        bySize.sort(Comparator.comparingInt((List<String> group) -> group.size()).reversed());
        List<List<String>> lots = new ArrayList<>();
        for (int i = 0; i < Math.min(ways, bySize.size()); i++) {
            lots.add(new ArrayList<>());
        }
        for (List<String> group : bySize) {
            List<String> smallest = lots.get(0);
            for (List<String> lot : lots) {
                if (lot.size() < smallest.size()) {
                    smallest = lot;
                }
            }
            smallest.addAll(group);
        }
        Map<String, Integer> order = new HashMap<>();
        int at = 0;
        for (String target : targets) {
            order.put(target, at++);
        }
        List<Set<String>> split = new ArrayList<>();
        for (List<String> lot : lots) {
            lot.sort(Comparator.comparingInt(order::get));
            split.add(new LinkedHashSet<>(lot));
        }
        return split;
    }

    private static List<String> findAbove(String type, Function<String, byte @Nullable []> bytesOf,
            Map<String, List<String>> known) {
        List<String> found = known.get(type);
        if (found != null) {
            return found;
        }
        List<String> made = new ArrayList<>();
        if (!type.startsWith("java/")) {
            byte[] bytes = bytesOf.apply(type);
            if (bytes != null) {
                made.addAll(ClassHeader.of(bytes).findAbove());
            }
            int nested = type.lastIndexOf('$');
            if (nested > 0) {
                made.add(type.substring(0, nested));
            }
        }
        known.put(type, made);
        return made;
    }

    private static String find(Map<String, String> leader, String type) {
        String at = type;
        for (String up = leader.get(at); up != null && !up.equals(at); up = leader.get(at)) {
            at = up;
        }
        leader.put(type, at);
        return at;
    }

    private static void join(Map<String, String> leader, String one, String other) {
        String a = find(leader, one);
        String b = find(leader, other);
        if (!a.equals(b)) {
            leader.put(a, b);
        }
    }
}
