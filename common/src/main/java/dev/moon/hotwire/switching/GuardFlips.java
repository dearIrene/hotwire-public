package dev.moon.hotwire.switching;

import dev.moon.hotwire.redefine.HandlerGuards;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GuardFlips {
    private static final Logger LOGGER = LoggerFactory.getLogger(GuardFlips.class);

    private final Map<String, ModMixins> mods;
    private final SwitchApplier.Game game;
    private final Set<String> onAtLaunch;
    private final Set<String> flipped = ConcurrentHashMap.newKeySet();

    GuardFlips(Map<String, ModMixins> mods, SwitchApplier.Game game, Set<String> onAtLaunch) {
        this.mods = mods;
        this.game = game;
        this.onAtLaunch = onAtLaunch;
    }

    boolean isFlipped(String id) {
        return this.flipped.contains(id);
    }

    record Plan(Map<String, Set<String>> flips, Set<String> rebuild, Set<String> flipped) {
        boolean isSitesAlone() {
            return this.rebuild.isEmpty();
        }
    }

    @Nullable Plan find(Set<String> switched, Set<String> onAfter, Collection<String> also, Set<String> replaced) {
        if (!also.isEmpty()) {
            return null;
        }
        Map<String, Set<String>> flips = new LinkedHashMap<>();
        Set<String> rebuild = new TreeSet<>();
        Set<String> flipped = new TreeSet<>();
        for (String id : switched) {
            ModMixins mixins = this.mods.get(id);
            if (mixins == null || !this.onAtLaunch.contains(id)
                    || (!mixins.callbacksOnly() && mixins.rebuildOnly().isEmpty())) {
                LOGGER.info("[Hotwire] {} is rebuilt, not flipped: known {}, on at launch {}", id, mixins != null,
                        this.onAtLaunch.contains(id));
                return null;
            }
            Set<String> own = new HashSet<>();
            for (String target : mixins.targets()) {
                if (this.game.findLoaded(target) == null) {
                    continue;
                }
                Set<String> here = new HashSet<>();
                for (String mixin : HandlerGuards.getGuardedMixins(target)) {
                    if (mixins.owns().contains(mixin.replace('.', '/'))) {
                        here.add(mixin);
                    }
                }
                boolean stuck = HandlerGuards.findUnflippableAt(target).stream()
                        .anyMatch(mixin -> mixins.owns().contains(mixin.replace('.', '/')));
                boolean present = HandlerGuards.getPresentMixins(target).stream()
                        .anyMatch(mixin -> mixins.owns().contains(mixin.replace('.', '/')));
                stuck |= HandlerGuards.findDirectModsAt(target).contains(id)
                        || HandlerGuards.findBehavingAt(target).stream()
                                .anyMatch(mixin -> mixins.owns().contains(mixin.replace('.', '/')));
                if (stuck || replaced.contains(target) || mixins.rebuildOnly().contains(target)
                        || !HandlerGuards.isWritten(target) || (!present && !here.isEmpty())) {
                    rebuild.add(target);
                } else if (!present && onAfter.contains(id)) {
                    rebuild.add(target);
                } else {
                    own.addAll(here);
                    if (!here.isEmpty()) {
                        flipped.add(target);
                    }
                }
            }
            flips.put(id, own);
        }
        Plan plan = new Plan(flips, rebuild, flipped);
        if (plan.isSitesAlone()) {
            switched.forEach(id -> {
                if (onAfter.contains(id)) {
                    this.flipped.remove(id);
                } else {
                    this.flipped.add(id);
                }
            });
        } else {
            this.flipped.removeAll(switched);
            LOGGER.info("[Hotwire] {} flips {} mixins and rebuilds {} classes: {}", switched,
                    flips.values().stream().mapToInt(Set::size).sum(), rebuild.size(),
                    rebuild.stream().limit(8).toList());
        }
        return plan;
    }

    int setSites(Plan plan, Set<String> onAfter) {
        int sites = 0;
        for (Map.Entry<String, Set<String>> flip : plan.flips().entrySet()) {
            sites += HandlerGuards.setOff(flip.getValue(), !onAfter.contains(flip.getKey()));
        }
        return sites;
    }

    final class Sites {
        private final @Nullable Plan plan;
        private final Set<String> onAfter;
        private boolean set;

        Sites(@Nullable Plan plan, Set<String> onAfter) {
            this.plan = plan;
            this.onAfter = onAfter;
        }

        Runnable setBefore(Runnable whilePaused) {
            return this.plan == null ? whilePaused : () -> {
                this.set();
                whilePaused.run();
            };
        }

        void set() {
            Plan planned = this.plan;
            if (planned != null && !this.set) {
                GuardFlips.this.setSites(planned, this.onAfter);
                this.set = true;
            }
        }

        void restore() {
            Plan planned = this.plan;
            if (planned != null && this.set) {
                GuardFlips.this.restoreSites(planned, this.onAfter);
            }
        }
    }

    void restoreSites(Plan plan, Set<String> onAfter) {
        for (Map.Entry<String, Set<String>> flip : plan.flips().entrySet()) {
            HandlerGuards.setOff(flip.getValue(), onAfter.contains(flip.getKey()));
        }
    }

    SwitchApplier.ApplyResult flip(Plan plan, Set<String> switched, Set<String> onAfter, Runnable release,
            Runnable whilePaused) {
        List<String> loaded = new ArrayList<>();
        List<String> notLoaded = new ArrayList<>();
        for (String id : switched) {
            ModMixins mixins = this.mods.get(id);
            for (String target : mixins == null ? Set.<String>of() : mixins.targets()) {
                (this.game.findLoaded(target) == null ? notLoaded : loaded).add(target);
            }
        }
        SwitchTimes.mark("pause game");
        long pausedAt = System.nanoTime();
        int sites = 0;
        try {
            sites = this.setSites(plan, onAfter);
            SwitchTimes.mark("flip sites");
            whilePaused.run();
            SwitchTimes.mark("paused: takeouts");
        } finally {
            release.run();
            LOGGER.info("[Hotwire] the game was paused {} ms", (System.nanoTime() - pausedAt) / 1_000_000);
        }
        LOGGER.info("[Hotwire] {} switched by {} callback sites in {} classes, no class redefined", switched, sites,
                loaded.size());
        return new SwitchApplier.ApplyResult(loaded, notLoaded, List.of(), List.of());
    }
}
