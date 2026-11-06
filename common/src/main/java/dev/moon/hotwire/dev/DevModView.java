package dev.moon.hotwire.dev;

import java.util.List;
import java.util.Map;

public record DevModView(String id, String name, DevState state, String line, int runningBuild, long builtAt,
        List<DevEvent> events, List<DevCrash> crashes, List<DevMixin> mixins, List<Integer> keptBuilds,
        Map<Integer, Integer> parents, Map<Integer, DevBuildInfo> infos) {
    public DevModView {
        infos = Map.copyOf(infos);
        keptBuilds = List.copyOf(keptBuilds);
        parents = Map.copyOf(parents);
        events = List.copyOf(events);
        crashes = List.copyOf(crashes);
        mixins = List.copyOf(mixins);
    }
}
