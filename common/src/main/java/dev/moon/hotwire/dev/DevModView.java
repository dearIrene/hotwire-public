package dev.moon.hotwire.dev;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public record DevModView(String id, String name, DevState state, String line, int runningBuild, long builtAt,
        List<DevEvent> events, List<DevCrash> crashes, List<DevMixin> mixins, List<Integer> savedBuilds,
        Map<Integer, Integer> parents, Map<Integer, DevBuildInfo> infos, Map<Integer, List<String>> throwsByBuild,
        Map<Integer, DevDelta> deltas, Map<Integer, String> names, Set<Integer> pinned, int codeBuild,
        boolean canUndo, Map<Integer, Long> sizes, List<Integer> cleared, @Nullable DevCompare compare) {
    public DevModView(String id, String name, DevState state, String line, int runningBuild, long builtAt,
            List<DevEvent> events, List<DevCrash> crashes, List<DevMixin> mixins, List<Integer> savedBuilds,
            Map<Integer, Integer> parents, Map<Integer, DevBuildInfo> infos, Map<Integer, List<String>> throwsByBuild,
            Map<Integer, DevDelta> deltas, Map<Integer, String> names, Set<Integer> pinned, int codeBuild,
            boolean canUndo, Map<Integer, Long> sizes, List<Integer> cleared) {
        this(id, name, state, line, runningBuild, builtAt, events, crashes, mixins, savedBuilds, parents, infos,
                throwsByBuild, deltas, names, pinned, codeBuild, canUndo, sizes, cleared, null);
    }

    public DevModView(String id, String name, DevState state, String line, int runningBuild, long builtAt,
            List<DevEvent> events, List<DevCrash> crashes, List<DevMixin> mixins, List<Integer> savedBuilds,
            Map<Integer, Integer> parents, Map<Integer, DevBuildInfo> infos, Map<Integer, List<String>> throwsByBuild,
            Map<Integer, DevDelta> deltas, Map<Integer, String> names, Set<Integer> pinned, int codeBuild,
            boolean canUndo) {
        this(id, name, state, line, runningBuild, builtAt, events, crashes, mixins, savedBuilds, parents, infos,
                throwsByBuild, deltas, names, pinned, codeBuild, canUndo, Map.of(), List.of(), null);
    }

    public DevModView(String id, String name, DevState state, String line, int runningBuild, long builtAt,
            List<DevEvent> events, List<DevCrash> crashes, List<DevMixin> mixins, List<Integer> savedBuilds,
            Map<Integer, Integer> parents, Map<Integer, DevBuildInfo> infos, Map<Integer, List<String>> throwsByBuild,
            Map<Integer, DevDelta> deltas, Map<Integer, String> names, Set<Integer> pinned) {
        this(id, name, state, line, runningBuild, builtAt, events, crashes, mixins, savedBuilds, parents, infos,
                throwsByBuild, deltas, names, pinned, runningBuild, false, Map.of(), List.of(), null);
    }

    public DevModView {
        sizes = Map.copyOf(sizes);
        cleared = List.copyOf(cleared);
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

    public DevModView withoutCompare() {
        return this.compare == null ? this : new DevModView(this.id, this.name, this.state, this.line,
                this.runningBuild, this.builtAt, this.events, this.crashes, this.mixins, this.savedBuilds,
                this.parents, this.infos, this.throwsByBuild, this.deltas, this.names, this.pinned, this.codeBuild,
                this.canUndo, this.sizes, this.cleared);
    }

    public boolean isTrying() {
        return this.codeBuild >= 0 && this.codeBuild != this.runningBuild;
    }

    public boolean hasBuild(int build) {
        return build == this.runningBuild || this.savedBuilds.contains(build);
    }
}
