package dev.moon.hotwire.dev;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record DevModView(String id, String name, DevState state, String line, int runningBuild, long builtAt,
        List<DevEvent> events, List<DevCrash> crashes, List<DevMixin> mixins, List<Integer> savedBuilds,
        Map<Integer, Integer> parents, Map<Integer, DevBuildInfo> infos, Map<Integer, List<String>> throwsByBuild,
        Map<Integer, DevDelta> deltas, Map<Integer, String> names, Set<Integer> pinned) {
    public DevModView {
        deltas = Map.copyOf(deltas);
        names = Map.copyOf(names);
        pinned = Set.copyOf(pinned);
        infos = Map.copyOf(infos);
        throwsByBuild = Map.copyOf(throwsByBuild);
        savedBuilds = List.copyOf(savedBuilds);
        parents = Map.copyOf(parents);
        events = List.copyOf(events);
        crashes = List.copyOf(crashes);
        mixins = List.copyOf(mixins);
    }

    public List<Integer> findBuilds() {
        List<Integer> builds = new ArrayList<>(this.savedBuilds);
        if (!builds.contains(this.runningBuild)) {
            builds.add(this.runningBuild);
        }
        builds.sort(Comparator.reverseOrder());
        return builds;
    }

    public boolean hasBuild(int build) {
        return build == this.runningBuild || this.savedBuilds.contains(build);
    }
}
