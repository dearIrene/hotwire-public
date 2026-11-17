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
    static final String FIELD_SETUP = "a field setup no site switches";

    private final Map<String, ModMixins> mods;
    private final SwitchApplier.Game game;
    private final Set<String> onAtLaunch;

    private final BiPredicate<String, String> mixedWith;
    private final Set<String> flipped = ConcurrentHashMap.newKeySet();

    private Set<String> flippedBefore = Set.of();

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
        this.flippedBefore = Set.copyOf(this.flipped);
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

        Map<String, String> whys = new TreeMap<>();
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
                    mixins.owns().forEach(mixin -> own.add(mixin.replace('/', '.')));
                    continue;
                }
                Set<String> here = new HashSet<>();
                for (String mixin : HandlerGuards.getGuardedMixins(target)) {
                    if (mixins.owns().contains(mixin.replace('.', '/'))) {
                        here.add(mixin);
                    }
                }

                own.addAll(here);
                Why why = this.findWhy(id, target, mixins, replaced, onAfter, !here.isEmpty());
                if (why != null) {
                    rebuild.add(target);
                    if (said && why == Why.STUCK) {
                        LOGGER.info("[Hotwire] {} cannot flip in {}: {}{}", id, target, findWhys(id, target, mixins),
                                HandlerGuards.isRebuilt(target) ? " (rebuilt since it loaded)" : "");
                    }
                    if (said) {
                        whys.put(target, why.words);
                    }
                } else if (!here.isEmpty()) {
                    flipped.add(target);
                }
            }
            flips.put(id, own);
        }
        if (said && !whys.isEmpty()) {
            Map<String, List<String>> byWhy = new TreeMap<>();
            whys.forEach((target, reason) -> byWhy.computeIfAbsent(reason, any -> new ArrayList<>()).add(target));
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

    private enum Why {
        STUCK("sites that cannot flip"),
        REPLACED("replaced"),
        REBUILD_ONLY("fields set up or an injector unknown"),

        NOT_WRITTEN("not written as it loaded"),
        NOT_IN_IT("mixins not in it"),

        WITHOUT("loaded or rebuilt without them");

        private final String words;

        Why(String words) {
            this.words = words;
        }
    }

    private @Nullable Why findWhy(String id, String target, ModMixins mixins, Set<String> replaced,
            Set<String> onAfter, boolean sited) {
        if (findStuck(id, target, mixins, false).blocks()) {
            return Why.STUCK;
        }
        if (replaced.contains(target)) {
            return Why.REPLACED;
        }
        if (mixins.rebuildOnly().contains(target)) {
            return Why.REBUILD_ONLY;
        }
        if (!HandlerGuards.isWritten(target)) {
            return Why.NOT_WRITTEN;
        }
        boolean present = HandlerGuards.getPresentMixins(target).stream()
                .anyMatch(mixin -> mixins.owns().contains(mixin.replace('.', '/')));
        if (!present && sited) {
            return Why.NOT_IN_IT;
        }
        return !present && onAfter.contains(id) && !this.mixedWith.test(target, id) ? Why.WITHOUT : null;
    }

    private record Stuck(boolean blocks, List<String> lines) {
    }

    private static Stuck findStuck(String id, String target, ModMixins mixins, boolean said) {
        List<String> lines = new ArrayList<>();
        Map<String, String> reasons = HandlerGuards.findWhyUnflippableAt(target);
        boolean blocks = false;
        for (String mixin : HandlerGuards.findUnflippableAt(target)) {
            blocks |= mixins.owns().contains(mixin.replace('.', '/'))
                    && !HandlerGuards.IN_CONSTRUCTOR.equals(reasons.get(mixin));
        }
        if (said) {
            reasons.forEach((mixin, reason) -> {
                if (mixins.owns().contains(mixin.replace('.', '/'))) {
                    lines.add(mixin.substring(mixin.lastIndexOf('.') + 1) + ": " + reason);
                }
            });
        }
        if (!blocks || said) {
            if (HandlerGuards.findDirectModsAt(target).contains(id)) {
                blocks = true;
                if (said) {
                    lines.add("code of its own called straight " + HandlerGuards.findPutInAt(target, id));
                }
            }
        }
        if (!blocks || said) {
            for (String mixin : HandlerGuards.findBehavingAt(target, mixins.owns())) {
                if (mixins.owns().contains(mixin.replace('.', '/'))) {
                    blocks = true;
                    if (said) {
                        Set<String> names = HandlerGuards.findBehavingNamesAt(target, mixin, mixins.owns());
                        lines.add(mixin.substring(mixin.lastIndexOf('.') + 1) + ": methods of its own the game calls"
                                + (names.isEmpty() ? "" : " (" + String.join(" ", names) + ")"));
                    }
                }
            }
        }

        if ((!blocks || said) && mixins.fieldsSetUp().contains(target)
                && !HandlerGuards.isFieldSitedFor(target, mixins.owns())) {
            blocks = true;
            if (said) {
                lines.add(FIELD_SETUP);
            }
        }
        return new Stuck(blocks, lines);
    }

    static List<String> findWhys(String id, String target, ModMixins mixins) {
        return findStuck(id, target, mixins, true).lines();
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
            if (planned != null) {
                GuardFlips.this.restoreFlipped();
            }
        }
    }

    void restoreSites(Plan plan, Set<String> onAfter) {
        setAll(plan, onAfter, false);
    }

    private void restoreFlipped() {
        this.flipped.retainAll(this.flippedBefore);
        this.flipped.addAll(this.flippedBefore);
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
        } catch (RuntimeException | LinkageError e) {
            this.restoreSites(plan, onAfter);
            this.restoreFlipped();
            throw e;
        } finally {
            release.run();
            LOGGER.info("[Hotwire] the game was paused {} ms", (System.nanoTime() - pausedAt) / 1_000_000);
        }
        LOGGER.info("[Hotwire] {} switched by {} callback sites in {} classes, no class redefined", switched, sites,
                loaded.size());
        return new SwitchApplier.ApplyResult(loaded, notLoaded, List.of(), List.of());
    }
}
