package dev.moon.hotwire.switching;

import dev.moon.hotwire.mixin_copy.MixinClassInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class MixinLists {
    private static final Logger LOGGER = LoggerFactory.getLogger(MixinLists.class);

    private MixinLists() {
    }

    static Map<String, Set<String>> findPatchersOf(Map<String, ModMixins> mods, Set<String> classes,
            Set<String> on, String except) {
        Map<String, Set<String>> patchers = new TreeMap<>();
        mods.forEach((id, mixins) -> {
            if (id.equals(except) || !on.contains(id)) {
                return;
            }
            for (String target : mixins.targets()) {
                if (classes.contains(target)) {
                    patchers.computeIfAbsent(target, t -> new TreeSet<>()).add(id);
                }
            }
        });
        return patchers;
    }

    static List<String> findPatching(Map<String, ModMixins> mods, Map<String, String> ownerOfConfig,
            List<String> configs, Set<String> classes) {
        List<String> out = new ArrayList<>();
        for (String config : configs) {
            String id = ownerOfConfig.get(config);
            ModMixins owner = id == null ? null : mods.get(id);
            if (owner == null || !Collections.disjoint(owner.getTargetsOf(config), classes)) {
                out.add(config);
            }
        }
        return out;
    }

    static Set<String> findDifferingFromLaunch(Map<String, ModMixins> mods, Set<String> onAtLaunch, Set<String> on,
            Predicate<String> flipped) {
        Set<String> targets = new HashSet<>();
        Set<String> differing = new TreeSet<>();
        mods.forEach((id, mixins) -> {
            if (onAtLaunch.contains(id) != on.contains(id) && !flipped.test(id)) {
                targets.addAll(mixins.targets());
                differing.add(id);
            }
        });
        LOGGER.info("[Hotwire] classes that load from now on are mixed afresh for {} mods that differ from launch: {}",
                differing.size(), differing.stream().limit(12).toList());
        return Set.copyOf(targets);
    }

    static Set<String> findClassesPatchedBy(Map<String, ModMixins> mods, Set<String> switched) {
        Set<String> targets = new TreeSet<>();
        for (String id : switched) {
            ModMixins mixins = mods.get(id);
            if (mixins != null) {
                targets.addAll(mixins.targets());
            }
        }
        return targets;
    }

    static List<String> getConfigsOf(Map<String, ModMixins> mods, Set<String> on, List<String> unowned,
            List<String> launchOrder) {
        List<String> ordered = new ArrayList<>();
        mods.forEach((id, mixins) -> {
            if (on.contains(id)) {
                ordered.addAll(mixins.configs());
            }
        });
        ordered.addAll(unowned);
        Map<String, Integer> launched = new HashMap<>();
        for (int i = 0; i < launchOrder.size(); i++) {
            launched.putIfAbsent(launchOrder.get(i), i);
        }
        ordered.sort(Comparator.comparingInt(config -> launched.getOrDefault(config, Integer.MAX_VALUE)));
        return ordered;
    }

    static Set<String> findTargetsOfChanged(@Nullable ModMixins mixins, Map<String, byte[]> classes,
            Predicate<String> known) {
        Set<String> targets = new TreeSet<>();
        if (mixins == null) {
            return targets;
        }
        classes.forEach((name, bytes) -> {
            if (!mixins.owns().contains(name)) {
                return;
            }
            try {
                targets.addAll(MixinClassInfo.read(bytes).targets());
            } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException unreadable) {
                LOGGER.warn("[Hotwire] the new {} is not a readable class, so its targets stay as they are", name);
            }
        });
        if (targets.stream().anyMatch(known.negate())) {
            targets.addAll(mixins.targets());
        }
        return targets;
    }
}
