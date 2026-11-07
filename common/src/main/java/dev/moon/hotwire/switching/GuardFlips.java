package dev.moon.hotwire.switching;

import dev.moon.hotwire.redefine.HandlerGuards;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GuardFlips {
    private static final Logger LOGGER = LoggerFactory.getLogger(GuardFlips.class);

    private final Map<String, ModMixins> mods;
    private final SwitchApplier.Game game;
    private final Set<String> onAtLaunch;

    private final BiPredicate<String, String> mixedWith;
    private final Set<String> flipped = ConcurrentHashMap.newKeySet();

    GuardFlips(Map<String, ModMixins> mods, SwitchApplier.Game game, Set<String> onAtLaunch,
            BiPredicate<String, String> mixedWith) {
        this.mods = mods;
        this.game = game;
        this.onAtLaunch = onAtLaunch;
        this.mixedWith = mixedWith;
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
        Plan plan = this.plan(switched, onAfter, also, replaced, true);
        if (plan == null) {
            return null;
        }
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
                    plan.flips().values().stream().mapToInt(Set::size).sum(), plan.rebuild().size(),
                    plan.rebuild().stream().limit(8).toList());
        }
        return plan;
    }

    @Nullable Plan plan(Set<String> switched, Set<String> onAfter, Collection<String> also, Set<String> replaced,
            boolean said) {
        if (!also.isEmpty()) {
            return null;
        }
        Map<String, Set<String>> flips = new LinkedHashMap<>();
        Set<String> rebuild = new TreeSet<>();
        Set<String> flipped = new TreeSet<>();

        Map<String, String> why = new TreeMap<>();
        for (String id : switched) {
            ModMixins mixins = this.mods.get(id);
            if (mixins == null || !this.onAtLaunch.contains(id)
                    || (!mixins.callbacksOnly() && mixins.rebuildOnly().isEmpty())) {
                if (said) {
                    LOGGER.info("[Hotwire] {} is rebuilt, not flipped: known {}, on at launch {}", id, mixins != null,
                            this.onAtLaunch.contains(id));
                }
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

                own.addAll(here);

                Map<String, String> whyStuck = HandlerGuards.findWhyUnflippableAt(target);
                boolean stuck = HandlerGuards.findUnflippableAt(target).stream()
                        .filter(mixin -> mixins.owns().contains(mixin.replace('.', '/')))
                        .anyMatch(mixin -> !HandlerGuards.IN_CONSTRUCTOR.equals(whyStuck.get(mixin)));
                boolean present = HandlerGuards.getPresentMixins(target).stream()
                        .anyMatch(mixin -> mixins.owns().contains(mixin.replace('.', '/')));
                stuck |= HandlerGuards.findDirectModsAt(target).contains(id)
                        || HandlerGuards.findBehavingAt(target).stream()
                                .anyMatch(mixin -> mixins.owns().contains(mixin.replace('.', '/')));

                stuck |= mixins.fieldsSetUp().contains(target) && !HandlerGuards.isFieldSitedFor(target, mixins.owns());
                if (stuck || replaced.contains(target) || mixins.rebuildOnly().contains(target)
                        || !HandlerGuards.isWritten(target) || (!present && !here.isEmpty())) {
                    rebuild.add(target);
                    if (said && stuck) {
                        List<String> whys = new ArrayList<>();
                        HandlerGuards.findWhyUnflippableAt(target).forEach((mixin, reason) -> {
                            if (mixins.owns().contains(mixin.replace('.', '/'))) {
                                whys.add(mixin.substring(mixin.lastIndexOf('.') + 1) + ": " + reason);
                            }
                        });
                        if (HandlerGuards.findDirectModsAt(target).contains(id)) {
                            whys.add("code of its own called straight " + HandlerGuards.findPutInAt(target, id));
                        }
                        HandlerGuards.findBehavingAt(target).stream()
                                .filter(mixin -> mixins.owns().contains(mixin.replace('.', '/')))
                                .forEach(mixin -> whys.add(mixin.substring(mixin.lastIndexOf('.') + 1)
                                        + ": methods of its own the game calls"));
                        LOGGER.info("[Hotwire] {} cannot flip in {}: {}", id, target, whys);
                    }
                    if (said) {
                        why.put(target, stuck ? "sites that cannot flip" : replaced.contains(target) ? "replaced"
                                : mixins.rebuildOnly().contains(target) ? "fields set up or an injector unknown"
                                : !HandlerGuards.isWritten(target) ? "not written as it loaded" : "mixins not in it");
                    }
                } else if (!present && onAfter.contains(id) && !this.mixedWith.test(target, id)) {
                    rebuild.add(target);
                    if (said) {
                        why.put(target, "loaded or rebuilt without them");
                    }
                } else {
                    if (!here.isEmpty()) {
                        flipped.add(target);
                    }
                }
            }
            flips.put(id, own);
        }
        if (said && !why.isEmpty()) {
            Map<String, List<String>> byWhy = new TreeMap<>();
            why.forEach((target, reason) -> byWhy.computeIfAbsent(reason, any -> new ArrayList<>()).add(target));
            byWhy.forEach((reason, targets) -> LOGGER.info("[Hotwire] {} rebuilds {} classes for {}: {}", switched,
                    targets.size(), reason, targets.stream().limit(6).toList()));
        }

        for (String id : switched) {
            ModMixins mixins = this.mods.get(id);
            if (mixins != null && onAfter.contains(id)) {
                Set<String> own = flips.computeIfAbsent(id, any -> new LinkedHashSet<>());
                for (String mixin : mixins.owns()) {
                    own.add(mixin.replace('/', '.'));
                }
            }
        }
        return new Plan(flips, rebuild, flipped);
    }

    int setSites(Plan plan, Set<String> onAfter) {
        return setAll(plan, onAfter, true);
    }

    private static int setAll(Plan plan, Set<String> onAfter, boolean asPlanned) {
        Set<String> off = new LinkedHashSet<>();
        Set<String> on = new LinkedHashSet<>();
        plan.flips().forEach((id, mixins) -> (onAfter.contains(id) == asPlanned ? on : off).addAll(mixins));
        return HandlerGuards.setOff(off, true) + HandlerGuards.setOff(on, false);
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
        setAll(plan, onAfter, false);
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
            SwitchTimes.mark("paused: removals");
        } finally {
            release.run();
            LOGGER.info("[Hotwire] the game was paused {} ms", (System.nanoTime() - pausedAt) / 1_000_000);
        }
        LOGGER.info("[Hotwire] {} switched by {} callback sites in {} classes, no class redefined", switched, sites,
                loaded.size());
        return new SwitchApplier.ApplyResult(loaded, notLoaded, List.of(), List.of());
    }
}
