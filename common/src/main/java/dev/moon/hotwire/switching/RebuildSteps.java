package dev.moon.hotwire.switching;

import dev.moon.hotwire.redefine.ClassRebuilder;
import dev.moon.hotwire.redefine.OffWindowObjects;
import dev.moon.hotwire.redefine.RebuildException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class RebuildSteps {
    private static final Logger LOGGER = LoggerFactory.getLogger(RebuildSteps.class);

    private RebuildSteps() {
    }

    static Runnable apply(ClassRebuilder rebuilder, ClassRebuilder.PreparedRebuild prepared,
            Supplier<List<String>> health, BiConsumer<Set<String>, Set<Class<?>>> afterRebuild,
            Consumer<Runnable> onGameThread, GameObjectRebuilder objectRebuilder, boolean inWorld,
            @Nullable Consumer<IntSupplier> catchUpLater, Set<String> flipped) throws RebuildException {
        LiveObjects.checkWhilePaused(prepared);
        SwitchTimes.mark("check paused");

        List<String> already = health.get();
        Set<String> rebuilt = getRebuilt(prepared);
        Set<Class<?>> rehashed = getRehashed(prepared);
        rebuilder.apply(prepared);
        SwitchTimes.mark("redefine");
        afterRebuild.accept(rebuilt, rehashed);
        SwitchTimes.mark("after rebuild");

        List<String> broken = new ArrayList<>(health.get());
        broken.removeAll(already);
        SwitchTimes.mark("health");
        if (!broken.isEmpty()) {
            LOGGER.error("[Hotwire] After the rebuild of {} the game no longer holds together: {}; it is put back",
                    prepared.splits().keySet(), broken);
            revert(rebuilder, prepared, afterRebuild);
            throw RebuildException.ofRestartOnly("the change leaves the game unable to run: "
                    + String.join("; ", broken) + ". It was put back as it was; it takes a restart",
                    new IllegalStateException(String.join("; ", broken)));
        }
        int notSetUp = 0;
        Set<String> changed = new HashSet<>(rebuilt);
        changed.addAll(flipped);
        Runnable rebuildObjects = () -> {
            objectRebuilder.rebuild(prepared.newlyBuilt(), changed, !prepared.pausedReloads().isEmpty());

            prepared.pausedReloads().forEach(OffWindowObjects::forget);
        };
        if (catchUpLater == null) {
            notSetUp = LiveObjects.replay(prepared, onGameThread);
            rebuilder.startStatics();
        } else {
            catchUpLater.accept(() -> {
                int later = LiveObjects.replay(prepared, onGameThread);
                rebuilder.startStatics();
                rebuildObjects.run();
                return later;
            });
        }
        SwitchTimes.mark("replay");
        if (notSetUp > 0) {
            revert(rebuilder, prepared, afterRebuild);
            String message = notSetUp + " objects made while a mod was off could not be given its setup again; it was"
                    + " put back as it was, and "
                    + (inWorld ? "it changes with the world saved and opened again" : "it takes a restart");
            IllegalStateException cause = new IllegalStateException(String.valueOf(prepared.replayed().keySet()));
            throw inWorld ? new RebuildException(message, cause, true) : RebuildException.ofRestartOnly(message, cause);
        }
        return catchUpLater == null ? rebuildObjects : () -> { };
    }

    static void revert(ClassRebuilder rebuilder, ClassRebuilder.PreparedRebuild prepared,
            BiConsumer<Set<String>, Set<Class<?>>> afterRebuild) throws RebuildException {
        rebuilder.revert(prepared);
        try {
            afterRebuild.accept(getRebuilt(prepared), getRehashed(prepared));
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("[Hotwire] After {} were put back, what the game holds of them could not be put right again",
                    prepared.splits().keySet(), e);
        }
    }

    static void revertWhilePaused(ClassRebuilder rebuilder, ClassRebuilder.PreparedRebuild prepared,
            BiConsumer<Set<String>, Set<Class<?>>> afterRebuild, Supplier<Runnable> hold) throws RebuildException {
        Runnable release = hold.get();
        try {
            revert(rebuilder, prepared, afterRebuild);
        } finally {
            release.run();
        }
    }

    private static Set<String> getRebuilt(ClassRebuilder.PreparedRebuild prepared) {
        Set<String> rebuilt = new HashSet<>();
        prepared.splits().keySet().forEach(type -> rebuilt.add(type.getName().replace('.', '/')));
        return rebuilt;
    }

    private static Set<Class<?>> getRehashed(ClassRebuilder.PreparedRebuild prepared) {
        Set<Class<?>> rehashed = new HashSet<>();
        prepared.splits().forEach((type, split) -> {
            if (split.rehashes()) {
                rehashed.add(type);
            }
        });
        return rehashed;
    }
}
