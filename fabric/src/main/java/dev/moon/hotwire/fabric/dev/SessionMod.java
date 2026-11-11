package dev.moon.hotwire.fabric.dev;

import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.dev.BuildSources;
import dev.moon.hotwire.dev.DevBuild;
import dev.moon.hotwire.dev.DevBuildInfo;
import dev.moon.hotwire.dev.DevCompare;
import dev.moon.hotwire.dev.DevCrash;
import dev.moon.hotwire.dev.DevDelta;
import dev.moon.hotwire.dev.DevEvent;
import dev.moon.hotwire.dev.DevMixin;
import dev.moon.hotwire.dev.DevState;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SessionMod {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionMod.class);
    final DevMod mod;

    final Object taking = new Object();
    volatile DevBuild running;

    final Path startJar;
    volatile int runningNumber;

    volatile int codeNumber;

    final Deque<String> savePoints = new ConcurrentLinkedDeque<>();

    volatile @Nullable DevBuild inFolders;

    volatile int lastRunning = -1;

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

    final Map<Path, List<String>> sources = new HashMap<>();

    final @Nullable BuildSources buildSources;

    volatile int pairA = -1;
    volatile int pairB = -1;
    volatile @Nullable DevCompare compare;

    volatile @Nullable CompareWindow comparing;

    final Map<Integer, Long> sizes = new HashMap<>();

    volatile boolean sizing;

    final Deque<ClearedBuild> cleared = new ArrayDeque<>();

    record ClearedBuild(SavedBuild build, @Nullable DevBuildInfo info, @Nullable DevDelta delta, @Nullable String name,
            boolean pinned) {
    }

    volatile @Nullable List<DevMixin> mixins;

    SessionMod(DevMod mod, DevBuild running, Path folder, @Nullable BuildSources sources) {
        this.mod = mod;
        Path project = mod.findSourceProject();
        if (sources != null || project == null) {
            this.buildSources = sources;
        } else {
            this.buildSources = new BuildSources(folder.resolve(mod.id()).resolve("sources"), project,
                    mod.findSourceRoots());
        }
        this.running = running;
        this.startJar = running.getJar();
        this.starts = mod.findStartClasses();
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    Map<Integer, Long> findSizes(Tasks tasks) {
        boolean known = this.sizes.containsKey(this.runningNumber)
                && this.saved.stream().allMatch(build -> this.sizes.containsKey(build.number()));
        if (!known && !this.sizing) {
            this.sizing = true;
            tasks.submit("sizing the builds of " + this.mod.id(), () -> {
                try {
                    this.fillSizes();
                } finally {
                    this.sizing = false;
                }
                return null;
            });
        }
        return Map.copyOf(this.sizes);
    }

    private void fillSizes() {
        Map<Integer, Path> jars = new LinkedHashMap<>();
        DevBuild ran;
        int ranNumber;
        synchronized (this) {
            ran = this.running;
            ranNumber = this.runningNumber;
            for (SavedBuild build : this.saved) {
                if (!this.sizes.containsKey(build.number())) {
                    jars.put(build.number(), build.jar());
                }
            }
            if (!this.sizes.containsKey(ranNumber)) {
                jars.put(ranNumber, ran.getJar());
            }
        }
        for (Map.Entry<Integer, Path> entry : jars.entrySet()) {
            int number = entry.getKey();
            DevBuild stored = number == ranNumber ? ran : DevBuild.readIndex(entry.getValue());
            long size = stored == null ? 0 : stored.getAdded();
            try {
                size += this.buildSources == null ? 0 : this.buildSources.readAdded(number);
            } catch (IOException e) {
                LOGGER.debug("[Hotwire] the size of build {} of {} could not be read", number, this.mod.id(), e);
            }
            synchronized (this) {
                this.sizes.put(number, size);
            }
        }
    }

    boolean isInUse(int build) {
        CompareWindow compared = this.comparing;
        return build == this.runningNumber || build == this.codeNumber
                || this.savePoints.contains(String.valueOf(build))
                || (compared != null && (compared.getA() == build || compared.getB() == build));
    }

    synchronized void addEvent(DevEvent event) {
        this.events.addFirst(event);
        while (this.events.size() > MOST_EVENTS) {
            this.events.removeLast();
        }
    }
}
