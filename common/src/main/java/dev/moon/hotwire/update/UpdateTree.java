package dev.moon.hotwire.update;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

public final class UpdateTree {
    private static final int FEW = 6;

    private UpdateTree() {
    }

    public record Node(UpdateOffer offer, List<Node> under, int neededBy) {
        public Node {
            under = List.copyOf(under);
        }

        public boolean isLibrary() {
            return this.neededBy > 0;
        }

        public long findTotalSize() {
            long total = this.offer.size();
            for (Node node : this.under) {
                total += node.findTotalSize();
            }
            return total;
        }
    }

    public static List<Node> of(List<UpdateOffer> offers) {
        Map<String, UpdateOffer> byProject = new HashMap<>();
        offers.forEach(offer -> byProject.put(offer.projectId(), offer));
        Map<String, List<UpdateOffer>> needing = new HashMap<>();
        for (UpdateOffer offer : offers) {
            for (String needed : Set.copyOf(offer.requiredProjects())) {
                if (byProject.containsKey(needed) && !needed.equals(offer.projectId())) {
                    needing.computeIfAbsent(needed, any -> new ArrayList<>()).add(offer);
                }
            }
        }
        int wide = offers.size() < FEW ? Integer.MAX_VALUE : Math.max(2, offers.size() / 3);
        Comparator<UpdateOffer> byName = Comparator.comparing(offer -> offer.name().toLowerCase(Locale.ROOT));
        Map<String, List<UpdateOffer>> children = new HashMap<>();
        Set<String> placed = new HashSet<>();
        for (UpdateOffer offer : offers) {
            String parent = findParent(offer, needing, wide);
            if (parent != null) {
                children.computeIfAbsent(parent, any -> new ArrayList<>()).add(offer);
                placed.add(offer.projectId());
            }
        }

        Set<String> reachable = new HashSet<>();
        for (UpdateOffer offer : offers) {
            if (!placed.contains(offer.projectId())) {
                markReachable(offer.projectId(), children, reachable);
            }
        }
        List<Node> top = new ArrayList<>();
        Set<String> built = new HashSet<>();
        for (UpdateOffer offer : offers) {
            if ((!placed.contains(offer.projectId()) || !reachable.contains(offer.projectId()))
                    && !built.contains(offer.projectId())) {
                top.add(build(offer, children, needing, byName, built));
            }
        }
        top.sort(Comparator.comparingInt((Node node) -> -node.neededBy()).thenComparing(Node::offer, byName));
        return List.copyOf(top);
    }

    private static @Nullable String findParent(UpdateOffer offer,
            Map<String, List<UpdateOffer>> needing, int wide) {
        String best = null;
        int fewest = Integer.MAX_VALUE;
        for (String needed : new TreeSet<>(offer.requiredProjects())) {
            List<UpdateOffer> others = needing.get(needed);
            if (others != null && !needed.equals(offer.projectId()) && others.size() < wide
                    && others.size() < fewest) {
                best = needed;
                fewest = others.size();
            }
        }
        return best;
    }

    private static void markReachable(String project, Map<String, List<UpdateOffer>> children, Set<String> reachable) {
        if (reachable.add(project)) {
            children.getOrDefault(project, List.of()).forEach(child -> markReachable(child.projectId(), children,
                    reachable));
        }
    }

    private static Node build(UpdateOffer offer, Map<String, List<UpdateOffer>> children,
            Map<String, List<UpdateOffer>> needing, Comparator<UpdateOffer> byName, Set<String> built) {
        built.add(offer.projectId());
        List<Node> under = new ArrayList<>();
        children.getOrDefault(offer.projectId(), List.of()).stream().sorted(byName)
                .filter(child -> !built.contains(child.projectId()))
                .forEach(child -> under.add(build(child, children, needing, byName, built)));
        return new Node(offer, under, needing.getOrDefault(offer.projectId(), List.of()).size());
    }
}
