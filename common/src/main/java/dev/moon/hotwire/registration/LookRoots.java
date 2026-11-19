package dev.moon.hotwire.registration;

import dev.moon.hotwire.add.LoaderModList;
import dev.moon.hotwire.classfile.GameNames;
import dev.moon.hotwire.seam.Games;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

final class LookRoots {
    static final int GAME_STEPS = 2;
    static final int PATCHED_STEPS = 4;

    static final int WRAPPED_STEPS = 3;

    static final int NESTED_STEPS = 64;

    static final int WRAPPER_STEPS = 9;

    static final int LARGEST = 100_000;

    record Roots(List<Object> holders, Set<Class<?>> statics, HandedSearch handed) {
    }

    private final Function<String, Set<String>> mixinTargets;
    private final Function<String, Set<String>> handedTo;
    private final Predicate<Class<?>> initialized;

    LookRoots(Function<String, Set<String>> mixinTargets, Function<String, Set<String>> handedTo,
            Predicate<Class<?>> initialized) {
        this.mixinTargets = mixinTargets;
        this.handedTo = handedTo;
        this.initialized = initialized;
    }

    Roots find(Collection<String> mods, Predicate<String> on, LoadedClasses scan, Set<String> targets,
            List<Object> entities, Predicate<Class<?>> others, boolean every) {
        Map<String, Class<?>> loaded = scan.byName();
        Map<String, Set<String>> used = scan.used();
        Map<String, List<Class<?>>> classesOf = scan.classesOf();

        Set<String> patched = new HashSet<>(targets);
        for (String mod : mods) {
            this.findTalksTo(mod, used.getOrDefault(mod, Set.of()), loaded, mods, on)
                    .forEach(other -> patched.addAll(this.mixinTargets.apply(other)));
        }
        List<Object> holders = findHolders(entities, patched);
        Set<Class<?>> statics = new LinkedHashSet<>();

        targets.stream().map(name -> loaded.get(name.replace('.', '/'))).filter(Objects::nonNull).filter(others)
                .forEach(statics::add);

        List<String> staying = classesOf.keySet().stream().filter(other -> (every || !mods.contains(other))
                && !other.equals(GameNames.HOTWIRE) && (Owners.isLibrary(other)
                        || (on.test(other) && Owners.isRecordedMod(other)))).toList();

        Map<String, List<Class<?>>> initialized = new HashMap<>();

        Set<String> listed = new HashSet<>();
        Function<String, List<Class<?>>> initializedOf = group -> initialized.computeIfAbsent(group,
                key -> classesOf.getOrDefault(key, List.of()).stream().filter(this.initialized).toList());
        for (String mod : mods) {
            Set<String> names = used.getOrDefault(mod, Set.of());
            names.stream().map(loaded::get).filter(Objects::nonNull).filter(others).forEach(statics::add);

            Set<String> talked = this.findTalksTo(mod, names, loaded, mods, on);

            List<String> talksTo = List.copyOf(talked);
            staying.forEach(talked::add);
            for (String other : talked) {
                if (listed.add(other)) {
                    statics.addAll(initializedOf.apply(other));
                }
            }
            for (String other : talksTo) {
                if (listed.add(LoadedClasses.NESTED + other)) {
                    statics.addAll(initializedOf.apply(LoadedClasses.NESTED + other));
                }
            }
        }

        if (!Games.getNow().findOutContent().isEmpty()) {
            loaded.forEach((name, type) -> {
                if (GameNames.isMinecraftInternal(name) && this.initialized.test(type)) {
                    statics.add(type);
                }
            });
        }

        Set<Object> handed = LoaderModList.findHanded(mods);
        Set<String> consumers = HandedSearch.findConsumers(handed);
        List<Class<?>> consumerClasses = new ArrayList<>();
        consumers.forEach(consumer -> consumerClasses.addAll(initializedOf.apply(consumer)));
        HandedSearch search = new HandedSearch(handed, consumers, consumerClasses);
        return new Roots(holders.stream().filter(one -> others.test(one.getClass())).toList(), statics, search);
    }

    private static List<Object> findHolders(List<Object> entities, Set<String> targets) {
        Object game = Games.getNow().getRoot();
        List<Object> holders = new ArrayList<>(List.of(game));
        holders.addAll(findGameObjectsBelow(game, 1));
        holders.addAll(entities);
        findGameObjectsBelow(game, PATCHED_STEPS).stream()
                .filter(one -> SuperclassNames.containsAny(one.getClass(), targets)).forEach(holders::add);

        List<Object> patched = holders.stream()
                .filter(one -> SuperclassNames.containsAny(one.getClass(), targets)).toList();
        patched.forEach(one -> holders.addAll(findGameObjectsBelow(one, 1)));

        patched.forEach(one -> holders.addAll(Fields.findInCollectionsOf(one,
                type -> GameNames.isMinecraft(type.getName()))));
        return holders;
    }

    private Set<String> findTalksTo(String mod, Set<String> names, Map<String, Class<?>> loaded,
            Collection<String> mods, Predicate<String> on) {
        Set<String> owners = new HashSet<>();
        Set<String> candidates = new HashSet<>(this.handedTo.apply(mod));
        for (String name : names) {
            Class<?> type = loaded.get(name);
            String owner = type == null ? null : Owners.findModOf(type);
            if (owner != null) {
                candidates.add(owner);
            }
        }
        for (String owner : candidates) {
            if (!owner.equals(mod) && !mods.contains(owner)
                    && ((Owners.isRecordedMod(owner) && on.test(owner))
                    || Owners.isLibrary(owner))) {
                owners.add(owner);
            }
        }
        return owners;
    }

    static List<Object> findGameObjectsBelow(Object holder, int steps) {
        return Fields.findBelow(holder, steps, type -> GameNames.isMinecraft(type.getName()));
    }
}
