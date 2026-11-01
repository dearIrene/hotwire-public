package dev.moon.hotwire.switching;

import dev.moon.hotwire.redefine.ClassRebuilder;
import dev.moon.hotwire.redefine.HandlerGuards;
import dev.moon.hotwire.redefine.Linker;
import dev.moon.hotwire.redefine.RebuildException;
import dev.moon.hotwire.redefine.ReplacedStatics;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AppliedSwitches {
    private static final Logger LOGGER = LoggerFactory.getLogger(AppliedSwitches.class);
    private final SwitchApplier owner;

    AppliedSwitches(SwitchApplier owner) {
        this.owner = owner;
    }

    SwitchApplier.ApplyResult applyNow(Set<String> switched, Set<String> onAfter,
            Runnable inPause) throws RebuildException {
        ReplacedStatics.beginSwitch(switched, onAfter);
        Runnable whilePaused = () -> {
            inPause.run();
            ReplacedStatics.putBackFor(onAfter);
        };
        Set<String> movedBefore = Linker.getMoved();
        SwitchApplier.OnState before = new SwitchApplier.OnState(this.owner.onConfigs, this.owner.onMods,
                this.owner.watched, this.owner.lateMixins);
        GuardFlips.Plan plan = HandlerGuards.ENABLED ? this.owner.guardFlips.find(switched, onAfter,
                this.owner.alsoRebuilt.apply(switched, onAfter), this.owner.replaced.keySet()) : null;
        this.owner.onConfigs = this.owner.getConfigsOf(onAfter);
        this.owner.onMods = Set.copyOf(onAfter);
        this.owner.watched = this.owner.preparedSwitches.findDifferingFromLaunch(onAfter);
        this.owner.lateMixins = ConfigReads.findLateMixins(this.owner.onConfigs, this.owner.ownerOfConfig,
                this.owner.onAtLaunch, this.owner.game);
        if (plan != null && plan.isSitesAlone()) {
            try {
                return this.owner.guardFlips.flip(plan, switched, onAfter, this.owner.redefinitionHold.get(),
                        whilePaused);
            } catch (RuntimeException | LinkageError e) {
                this.restore(before);
                throw e;
            }
        }
        SwitchApplier.DryRun dryRun;
        GuardFlips.Sites sites = this.owner.guardFlips.new Sites(plan, onAfter);
        this.owner.duckRide.arm(movedBefore);
        try {
            this.owner.onlyRebuild = plan == null ? null : plan.rebuild();
            dryRun = this.owner.dryRun(switched, onAfter, true);
            SwitchTimes.mark("mixins retained");
            ClassRebuilder.PreparedRebuild prepared = dryRun.prepared();

            Runnable beforeChange = Collections.disjoint(switched, onAfter) ? sites::set : () -> { };
            if (prepared != null) {
                this.owner.applyWhilePaused(prepared, beforeChange, sites.setBefore(whilePaused),
                        plan == null ? Set.of() : plan.flipped());
            } else {
                Runnable release = this.owner.redefinitionHold.get();
                try {
                    sites.set();
                } finally {
                    release.run();
                }
            }
        } catch (RebuildException | RuntimeException | LinkageError e) {
            this.owner.duckRide.disarm();
            sites.restore();
            this.restore(before);
            throw e;
        } finally {
            this.owner.onlyRebuild = null;
        }
        try {
            this.rebuildLoadedMeanwhile(dryRun.applied().notLoaded(), onAfter);
        } catch (RebuildException | RuntimeException | LinkageError e) {
            this.owner.duckRide.disarm();
            ClassRebuilder.PreparedRebuild prepared = dryRun.prepared();
            if (prepared != null) {
                RebuildSteps.revertWhilePaused(this.owner.rebuilder, prepared, this.owner.afterRebuild,
                        this.owner.redefinitionHold);
            }
            this.restore(before);
            throw e;
        }
        SwitchTimes.mark("loaded meanwhile");
        DuckRebridge.runAfterSwitch(this.owner.bridged, this.owner.duckRide.disarm(), this.owner.mods,
                this.owner.game, this.owner.rebuilder);
        SwitchTimes.mark("duck bridges");
        return dryRun.applied();
    }

    void restore(SwitchApplier.OnState state) {
        this.owner.onConfigs = state.configs();
        this.owner.onMods = state.mods();
        this.owner.watched = state.watched();
        this.owner.lateMixins = state.late();
    }

    void rebuildLoadedMeanwhile(List<String> notLoaded, Set<String> onAfter) throws RebuildException {
        Map<Class<?>, byte[]> originals = new LinkedHashMap<>();
        for (String target : notLoaded) {
            Class<?> loaded = this.owner.game.findLoaded(target);
            byte[] original = loaded == null ? null : this.owner.findOriginal(target);
            if (original != null) {
                originals.put(loaded, original);
            }
        }
        if (originals.isEmpty()) {
            return;
        }
        LOGGER.info("[Hotwire] {} loaded while the switch ran and are rebuilt after it", originals.keySet().stream()
                .map(Class::getName).toList());
        this.owner.applyWhilePaused(this.owner.preparedSwitches.prepare(originals, originals, Map.of(),
                this.owner.onConfigs, onAfter, Set.of(),
                "rebuild"), () -> { }, Set.of());
    }
}
