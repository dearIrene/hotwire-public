package dev.moon.hotwire.switching;

import dev.moon.hotwire.redefine.ClassRebuilder;
import dev.moon.hotwire.redefine.RebuildException;
import dev.moon.hotwire.redefine.StopPoint;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class PreparedSwitches {
    private static final Logger LOGGER = LoggerFactory.getLogger(PreparedSwitches.class);
    private final SwitchApplier owner;

    PreparedSwitches(SwitchApplier owner) {
        this.owner = owner;
    }

    Map<String, byte[]> findRunningBytes(Collection<String> internalNames) {
        String loading = LoadTimeMixer.findLoading();
        Map<String, byte[]> found = new HashMap<>();
        List<Class<?>> toRead = new ArrayList<>();
        for (String name : internalNames) {
            Class<?> loaded = this.owner.game.findLoaded(name);

            if (loaded == null || name.startsWith("java/")) {
                continue;
            }
            if (loading != null && loading.startsWith(name + "$")) {
                byte[] known = this.owner.rebuilder.getLoadedBytes().findKnown(loaded);
                if (known != null) {
                    found.put(name, known);
                }
            } else {
                toRead.add(loaded);
            }
        }
        if (!toRead.isEmpty()) {
            List<String> refused = new ArrayList<>();
            try {
                found.putAll(this.owner.rebuilder.getLoadedBytes().makeGameClassReader(toRead, refused));
            } catch (RebuildException e) {
                LOGGER.warn("[Hotwire] the running bytes of {} could not be read, so references to them stay as mixed",
                        internalNames, e);
            }
            if (!refused.isEmpty()) {
                LOGGER.warn("[Hotwire] the running bytes of {} could not be read, so references to them stay as mixed",
                        refused);
            }
        }
        return found;
    }

    ClassRebuilder.PreparedRebuild prepare(Map<Class<?>, byte[]> originals, Map<Class<?>, byte[]> before,
            Map<String, byte[]> classes, List<String> configs, Set<String> on, Set<String> replacing, String what)
            throws RebuildException {
        StopPoint.check();
        Map<String, byte[]> current = this.owner.rebuilder.getLoadedBytes().readCurrent(originals.keySet());
        SwitchTimes.mark("read back");
        Map<String, Set<String>> carriedNow = new HashMap<>();
        Map<String, Set<String>> atLaunch = new HashMap<>();
        Set<String> rebuilt = new HashSet<>();
        before.forEach((type, old) -> {
            String name = type.getName().replace('.', '/');
            byte[] bytes = Objects.requireNonNull(current.get(name));
            rebuilt.add(name);

            Set<String> launched = new HashSet<>(this.owner.rebuilder.launchMixins(type, bytes, old));
            Set<String> applied = this.owner.rebuilder.getAppliedMixins(type, bytes, old);

            launched.removeIf(mixin -> {
                String owner = this.owner.carriedMixins.findModMaking(mixin, id -> true);
                return owner != null && (!on.contains(owner)
                        || (this.owner.updatedLive.contains(owner) && !applied.contains(mixin)));
            });
            atLaunch.put(name, launched);

            Set<String> now = new HashSet<>(applied);
            atLaunch.get(name).stream()
                    .filter(mixin -> this.owner.carriedMixins.findModShipping(mixin.replace('.', '/')) != null)
                    .forEach(now::add);
            carriedNow.put(name, now);
        });
        List<String> patching = this.owner.findPatching(configs, rebuilt);

        LaunchDecisions.Result decided = this.owner.decisions.of(patching, carriedNow, on::contains);
        SwitchTimes.mark("marks and decisions");
        ClassRebuilder.PreparedRebuild mixed = this.owner.rebuilder.prepare(originals, current, classes,
                decided.configs(),
                patching, on::contains);
        SwitchTimes.mark("mix");

        Set<String> staying = new HashSet<>(on);
        staying.removeAll(replacing);
        List<String> lost = this.owner.carriedMixins.findLost(carriedNow, mixed.carried(), mixed.mixed(), staying,
                decided::isAskedAgain, replacing);
        if (!lost.isEmpty()) {
            throw RebuildException.ofLosing("the " + what + " would drop patches of mods that stay on: " + lost,
                    new IllegalStateException(String.join(", ", lost)),
                            this.owner.carriedMixins.findOwnersOf(lost, on));
        }

        ClassRebuilder.PreparedRebuild prepared;
        try {
            prepared = LiveObjects.check(mixed, current, this.owner.rebuiltWhilePaused,
                this.owner.game,
                this.owner::findOriginal, this.owner.rebuilder, what, mixin -> {
                    String owner = this.owner.carriedMixins.findModMaking(mixin, id -> true);
                    return owner != null && !on.contains(owner);
                }, type -> {
                    String shipping = this.owner.carriedMixins.findModShipping(type);
                    return shipping != null && !on.contains(shipping);
                });
        } finally {
            SwitchTimes.mark("live objects");
        }
        return prepared;
    }

    Set<String> findDifferingFromLaunch(Set<String> on) {
        return MixinLists.findDifferingFromLaunch(this.owner.mods, this.owner.onAtLaunch, on,
                this.owner.guardFlips::isFlipped);
    }
}
