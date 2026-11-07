package dev.moon.hotwire.fabric.dev;

import dev.moon.hotwire.dev.DevBuild;
import dev.moon.hotwire.dev.DevBuildInfo;
import dev.moon.hotwire.dev.DevCrash;
import dev.moon.hotwire.dev.DevDelta;
import dev.moon.hotwire.dev.DevEvent;
import dev.moon.hotwire.dev.DevMixin;
import dev.moon.hotwire.dev.DevState;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

final class SessionMod {
    final DevMod mod;

    final Object taking = new Object();
    volatile DevBuild running;
    volatile int runningNumber;

    final Deque<SavedBuild> saved = new ArrayDeque<>();
    final Map<Integer, Integer> parents = new HashMap<>();

    final Map<Integer, DevBuildInfo> infos = new HashMap<>();

    final Map<Integer, Map<String, Integer>> throwsByBuild = new HashMap<>();
    final Set<Integer> autoRolled = new HashSet<>();

    final Map<Integer, DevDelta> deltas = new HashMap<>();
    final Map<Integer, String> names = new HashMap<>();
    final Set<Integer> pinned = new HashSet<>();
    volatile long builtAt = System.currentTimeMillis();

    volatile boolean crashTold;
    int builds;

    long goneSince;

    int halfTries;

    final Set<String> starts;
    volatile DevState state = DevState.UP_TO_DATE;
    volatile String line = "";
    private static final int MOST_EVENTS = 40;
    final Deque<DevEvent> events = new ArrayDeque<>();
    final Map<String, DevCrash> crashes = new LinkedHashMap<>();

    volatile @Nullable List<DevMixin> mixins;

    SessionMod(DevMod mod, DevBuild running) {
        this.mod = mod;
        this.running = running;
        this.starts = mod.findStartClasses();
    }

    synchronized void addEvent(DevEvent event) {
        this.events.addFirst(event);
        while (this.events.size() > MOST_EVENTS) {
            this.events.removeLast();
        }
    }
}
