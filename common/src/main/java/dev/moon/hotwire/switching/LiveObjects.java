package dev.moon.hotwire.switching;

import dev.moon.hotwire.redefine.ClassRebuilder;
import dev.moon.hotwire.redefine.ClassSplit;
import dev.moon.hotwire.redefine.ConstructedFields;
import dev.moon.hotwire.redefine.HeapCensus;
import dev.moon.hotwire.redefine.InitHookReplay;
import dev.moon.hotwire.redefine.OffWindowObjects;
import dev.moon.hotwire.redefine.OffWindowReplay;
import dev.moon.hotwire.redefine.RebuildException;
import dev.moon.hotwire.redefine.StopPoint;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LiveObjects {
    private static final Logger LOGGER = LoggerFactory.getLogger(LiveObjects.class);

    private static final ThreadLocal<Boolean> AHEAD = ThreadLocal.withInitial(() -> false);

    private LiveObjects() {
    }

    static void setAhead(boolean ahead) {
        AHEAD.set(ahead);
    }

    static Predicate<Class<?>> makeAliveCheck(Supplier<Class<?>[]> loaded) {
        boolean ahead = AHEAD.get();
        return type -> ahead || HeapCensus.hasObjectsOf(type, loaded);
    }

    static ClassRebuilder.PreparedRebuild check(ClassRebuilder.PreparedRebuild prepared, Map<String, byte[]> current,
            Predicate<Class<?>> recomputed, SwitchApplier.Game game, Function<String, byte @Nullable []> originalOf,
            ClassRebuilder rebuilder, String what) throws RebuildException {
        StopPoint.check();

        Predicate<Class<?>> alive = makeAliveCheck(rebuilder::getLoadedClasses);
        Map<Class<?>, InitHookReplay.Due> hooked = findHooked(prepared);
        hooked.keySet().removeIf(recomputed);
        List<String> entries = findRegistryEntries(hooked, game);
        if (!entries.isEmpty()) {
            throw RebuildException.ofRestartOnly("the " + what + " changes how entries of the game's registries are"
                    + " made: " + entries + ". Those are made once, when the game starts, so it takes a restart",
                    new IllegalStateException(String.join(", ", entries)));
        }
        ConstructedFields.Unmade unset = InitHookReplay.findUnset(hooked, game::isWorldObject, game.isInWorld(),
                alive);
        if (!unset.lines().isEmpty()) {
            throw makeRefusal(what, unset);
        }
        Map<Class<?>, ClassSplit.Result> retained = new LinkedHashMap<>(prepared.splits());
        retained.keySet().removeIf(recomputed);
        ClassRebuilder.PreparedRebuild out = withReloading(prepared, retained, game, originalOf,
                rebuilder::findLaunchBytes);
        Map<Class<?>, byte[]> targets = new LinkedHashMap<>();
        retained.forEach((type, split) -> targets.put(type, prepared.mixed().getOrDefault(
                type.getName().replace('.', '/'), split.target())));
        Map<String, byte[]> running = new HashMap<>(current);
        running.replaceAll((name, bytes) -> Objects.requireNonNullElse(rebuilder.findMixedNow(name), bytes));
        Predicate<Class<?>> live = type -> !game.isWorldObject(type) || game.isInWorld();
        Function<String, Set<String>> replayed = name -> prepared.replayed().getOrDefault(name, Set.of());
        Supplier<ConstructedFields.Unmade> unmade = () -> ConstructedFields.findUnmade(targets, running, live,
                game::isWorldObject, originalOf, OffWindowObjects::countAlive, replayed, alive,
                rebuilder::findLaunchBytes);
        ConstructedFields.Unmade now = unmade.get();
        if (now.lines().isEmpty()) {
            return out;
        }
        if (!ConstructedFields.findUnmade(targets, running, live, game::isWorldObject, originalOf, name -> 0, replayed,
                alive, rebuilder::findLaunchBytes).lines().isEmpty()) {
            throw makeRefusal(what, now);
        }

        return out.withPausedCheck(() -> new ConstructedFields.Unmade(unmade.get().lines(), game.isInWorld()));
    }

    static List<String> findRegistryEntries(Map<Class<?>, InitHookReplay.Due> hooked,
            SwitchApplier.Game game) {
        List<String> found = new ArrayList<>();
        hooked.forEach((type, due) -> due.statics().keySet().forEach(name -> {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(null);
                if (value != null && game.isRegistryEntry(value)) {
                    found.add(type.getName() + "." + name);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.debug("[Hotwire] {}.{} could not be read before its remake", type.getName(), name, e);
            }
        }));
        return found;
    }

    private static ClassRebuilder.PreparedRebuild withReloading(ClassRebuilder.PreparedRebuild prepared,
            Map<Class<?>, ClassSplit.Result> retained, SwitchApplier.Game game,
            Function<String, byte @Nullable []> originalOf, Function<String, byte @Nullable []> launchOf) {
        Set<String> reload = new TreeSet<>();
        retained.forEach((type, split) -> {
            String name = type.getName().replace('.', '/');

            byte[] mixed = prepared.mixed().getOrDefault(name, split.target());
            byte[] original = originalOf.apply(name);
            byte[] launched = launchOf.apply(name);
            boolean madeWithout = OffWindowObjects.countAliveFromReload(name) > 0 || (launched != null
                    && !ConstructedFields.findSetOutsideConstructors(mixed, launched).isEmpty());
            if (original != null && !game.isWorldObject(type) && madeWithout
                    && !ConstructedFields.findSetOutsideConstructors(mixed, original).isEmpty()) {
                reload.add(name);
            }
        });
        if (reload.isEmpty()) {
            return prepared;
        }
        LOGGER.info("[Hotwire] objects of {} made without a mod's setup are made anew by a resource reload", reload);
        return prepared.withPausedReloads(reload);
    }

    static void checkWhilePaused(ClassRebuilder.PreparedRebuild prepared) throws RebuildException {
        Supplier<ConstructedFields.Unmade> check = prepared.pausedCheck();
        if (check == null) {
            return;
        }
        long began = System.nanoTime();
        OffWindowObjects.awaitCollection();
        ConstructedFields.Unmade unmade = check.get();
        if (!unmade.lines().isEmpty()) {
            throw makeRefusal("rebuild", unmade);
        }
        LOGGER.info("[Hotwire] the objects made while a mod was out of their classes are gone ({} ms)",
                (System.nanoTime() - began) / 1_000_000);
    }

    static int replay(ClassRebuilder.PreparedRebuild prepared, Consumer<Runnable> onGameThread) {
        if (prepared.replayed().isEmpty() && prepared.hooked().isEmpty()) {
            return 0;
        }

        if (!prepared.replayed().isEmpty()) {
            OffWindowObjects.awaitCollection();
        }
        AtomicInteger failed = new AtomicInteger();
        onGameThread.accept(() -> {
            failed.addAndGet(InitHookReplay.run(findHooked(prepared)));
            prepared.splits().keySet().forEach(type -> {
                Set<String> fields = prepared.replayed().get(type.getName().replace('.', '/'));
                if (fields != null) {
                    failed.addAndGet(OffWindowReplay.run(type, fields));
                }
            });
        });
        return failed.get();
    }

    private static Map<Class<?>, InitHookReplay.Due> findHooked(ClassRebuilder.PreparedRebuild prepared) {
        Map<Class<?>, InitHookReplay.Due> hooked = new LinkedHashMap<>();
        prepared.splits().keySet().forEach(type -> Optional.ofNullable(prepared.hooked().get(type.getName()
                .replace('.', '/'))).ifPresent(due -> hooked.put(type, due)));
        return hooked;
    }

    static void reopenFirst(Set<String> switched, Set<String> onAfter, Predicate<String> tookFrom,
            SwitchApplier.Game game) throws RebuildException {
        List<String> back = switched.stream().filter(onAfter::contains).filter(tookFrom).toList();
        if (!back.isEmpty() && game.isInWorld()) {
            throw new RebuildException(back + " come back to what other mods or the game held in its place while it"
                    + " was off, and this world's objects were made from that: they are the world's, so it changes"
                    + " with the world saved and opened again", new IllegalStateException(String.valueOf(back)), true);
        }
    }

    static void reopenFirstOff(Set<String> switched, Set<String> onAfter, Supplier<Set<String>> calledByWorld,
            SwitchApplier.Game game) throws RebuildException {
        List<String> off = switched.stream().filter(id -> !onAfter.contains(id)).toList();
        if (off.isEmpty() || !game.isInWorld()) {
            return;
        }
        Set<String> called = calledByWorld.get();
        List<String> stay = off.stream().filter(called::contains).toList();
        if (!stay.isEmpty()) {
            throw new RebuildException(stay + " left code of theirs in what this world's connection runs for every"
                    + " packet, and only a new connection is made without it: it is the world's, so it changes with"
                    + " the world saved and opened again", new IllegalStateException(String.valueOf(stay)), true);
        }
    }

    private static RebuildException makeRefusal(String what, ConstructedFields.Unmade unmade) {
        String message = "the " + what + " changes how objects the game already made are built: "
                + String.join("; ", unmade.lines()) + ". Those objects would break, so this can't change while"
                + " they live: " + (unmade.reopenFixes() ? "they are the world's, so it changes with the world"
                + " saved and opened again" : "it takes a restart");
        IllegalStateException cause = new IllegalStateException(String.join("; ", unmade.lines()));
        return unmade.reopenFixes() ? RebuildException.ofWorldBuilt(message, cause)
                : RebuildException.ofRestartOnly(message, cause);
    }
}
