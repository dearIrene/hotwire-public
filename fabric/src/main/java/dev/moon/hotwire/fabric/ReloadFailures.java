package dev.moon.hotwire.fabric;

import com.google.common.collect.MapMaker;
import dev.moon.hotwire.fabric.add.LoaderModList;
import dev.moon.hotwire.fabric.registration.Fields;
import dev.moon.hotwire.fabric.registration.ReloadedLists;
import dev.moon.hotwire.redefine.FieldSlots;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.util.Unit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ReloadFailures {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReloadFailures.class);

    private static final Set<ReloadInstance> RUN_AGAIN = Collections.newSetFromMap(new MapMaker().weakKeys()
            .makeMap());

    private static volatile WeakReference<LoadingOverlay> lastMade = new WeakReference<>(null);

    private ReloadFailures() {
    }

    public static void onOverlayMade(LoadingOverlay overlay) {
        lastMade = new WeakReference<>(overlay);
    }

    private static @Nullable LoadingOverlay findFailedOverlay() {
        LoadingOverlay shown = ModViews.findLoadingOverlay();
        return shown != null ? shown : lastMade.get();
    }

    public static boolean runAgainOnFailure() {
        LoadingOverlay failed = findFailedOverlay();
        ReloadInstance reload = failed == null ? null : OngoingReload.findReloadOf(failed);
        Set<String> off = LoaderModList.getHidden();
        String reason = findWhyLeftToGame(failed, reload, off);
        if (reason != null || failed == null || reload == null) {
            LOGGER.info("[Hotwire] a resource reload failed; the game answers it, as {}", reason);
            return false;
        }
        Throwable cause = findFailure(reload);
        if (!ReloadedLists.takeOutUnmade(off)) {
            LOGGER.warn("[Hotwire] a resource reload failed with {} off, on nothing Hotwire could take out; the game"
                    + " answers it", off, cause);
            return false;
        }
        LOGGER.warn("[Hotwire] a resource reload failed with {} off; what the resources could not make is out, and"
                + " the reload runs again", off, cause);
        return runAgain(Minecraft.getInstance(), failed, off) != null;
    }

    private static @Nullable String findWhyLeftToGame(@Nullable LoadingOverlay failed, @Nullable ReloadInstance reload,
            Set<String> off) {
        if (failed == null) {
            return "no loading overlay holds it";
        }
        if (reload == null || findFailure(reload) == null) {
            return "its overlay names no failed reload";
        }
        if (off.isEmpty()) {
            return "no mod is off";
        }
        return RUN_AGAIN.contains(reload) ? "it is one Hotwire ran again" : null;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    static @Nullable ReloadInstance runAgain(Minecraft game, LoadingOverlay failed, Collection<String> off) {
        Consumer<Optional<Throwable>> onFinish = findOnFinish(failed);
        if (onFinish == null || !(game.getResourceManager() instanceof ReloadableResourceManager manager)) {
            LOGGER.error("[Hotwire] the failed resource reload cannot run again; the game answers it");
            return null;
        }
        ReloadInstance[] again = new ReloadInstance[1];
        ResourceReload.startUnprofiled(() -> again[0] = manager.createReload(ForkJoinPool.commonPool(), game,
                CompletableFuture.completedFuture(Unit.INSTANCE), game.getResourcePackRepository().openAllSelected()));
        RUN_AGAIN.add(again[0]);

        Consumer<Optional<Throwable>> once = makeOnce(onFinish);
        again[0].done().whenCompleteAsync((ended, thrown) -> once.accept(Optional.ofNullable(
                findFailure(again[0]))), game);
        ModViews.showOverlay(new LoadingOverlay(game, again[0], once, isFadingIn(failed)));
        LOGGER.info("[Hotwire] the resource reload runs again, with {} off", off);
        return again[0];
    }

    private static Consumer<Optional<Throwable>> makeOnce(Consumer<Optional<Throwable>> onFinish) {
        AtomicBoolean ran = new AtomicBoolean();
        return result -> {
            if (ran.compareAndSet(false, true)) {
                try {
                    onFinish.accept(result);
                } catch (RuntimeException e) {
                    LOGGER.error("[Hotwire] the game's end of the resource reload run again threw", e);
                }
            }
        };
    }

    static @Nullable Throwable findFailure(ReloadInstance reload) {
        CompletableFuture<?> done = reload.done();
        return done.isCompletedExceptionally() ? done.exceptionNow() : null;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Consumer<Optional<Throwable>> findOnFinish(LoadingOverlay overlay) {
        for (FieldSlots.Slot field : Fields.getDeclared(LoadingOverlay.class)) {
            try {
                if (!field.isStatic() && Consumer.class.isAssignableFrom(field.getType())) {
                    return (Consumer<Optional<Throwable>>) field.get(overlay);
                }
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean isFadingIn(LoadingOverlay overlay) {
        for (FieldSlots.Slot field : Fields.getDeclared(LoadingOverlay.class)) {
            try {
                if (!field.isStatic() && field.getType() == boolean.class && field.get(overlay) instanceof Boolean on) {
                    return on;
                }
            } catch (IllegalAccessException e) {
                return false;
            }
        }
        return false;
    }
}
