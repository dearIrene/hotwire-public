package dev.moon.hotwire.dev;

import java.util.List;

public record DevModView(String id, String name, DevState state, String line, int runningBuild, long builtAt,
        List<DevEvent> events, List<DevCrash> crashes, List<DevMixin> mixins, List<Integer> keptBuilds) {
    public DevModView {
        keptBuilds = List.copyOf(keptBuilds);
        events = List.copyOf(events);
        crashes = List.copyOf(crashes);
        mixins = List.copyOf(mixins);
    }
}
