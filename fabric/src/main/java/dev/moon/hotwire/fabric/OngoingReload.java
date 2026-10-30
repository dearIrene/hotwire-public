package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.registration.Fields;
import dev.moon.hotwire.redefine.FieldSlots;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class OngoingReload {
    private static final Logger LOGGER = LoggerFactory.getLogger(OngoingReload.class);
    private static final Duration WAIT = Duration.ofMinutes(2);

    private OngoingReload() {
    }

    static boolean await(Minecraft game) {
        BooleanSupplier done = findOngoing(game);
        if (done == null) {
            return false;
        }
        long deadline = System.nanoTime() + WAIT.toNanos();
        if (game.isSameThread()) {
            game.managedBlock(() -> {
                BackgroundReload.finishIfDone();
                return done.getAsBoolean() || System.nanoTime() > deadline;
            });
        } else {
            while (!done.getAsBoolean() && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return true;
                }
            }
        }
        if (done.getAsBoolean()) {
            LOGGER.info("[Hotwire] a reload under way was waited for");
        } else {
            LOGGER.warn("[Hotwire] a reload under way was waited for, and did not end");
        }
        return true;
    }

    private static @Nullable BooleanSupplier findOngoing(Minecraft game) {
        LoadingOverlay loading = ModViews.findLoadingOverlay();
        ReloadInstance ongoing = loading == null ? null : findReloadOf(loading);
        if (ongoing != null) {
            return ongoing.isDone() ? null : ongoing::isDone;
        }
        List<String> seen = new ArrayList<>();
        for (FieldSlots.Slot field : Fields.getDeclared(Minecraft.class)) {
            if (!field.isStatic() && CompletableFuture.class.isAssignableFrom(field.getType())) {
                try {
                    Object value = field.get(game);
                    String state = "null";
                    if (value instanceof CompletableFuture<?> future) {
                        state = future.isDone() ? "done" : "pending";
                        if (!future.isDone()) {
                            return future::isDone;
                        }
                    }
                    seen.add(field.getName() + "=" + state);
                } catch (IllegalAccessException e) {
                    seen.add(field.getName() + "=closed");
                }
            }
        }
        LOGGER.info("[Hotwire] no reload under way: futures {}, overlay {}", seen, loading);
        return null;
    }

    static @Nullable ReloadInstance findReloadOf(LoadingOverlay overlay) {
        for (FieldSlots.Slot field : Fields.getDeclared(LoadingOverlay.class)) {
            if (ReloadInstance.class.isAssignableFrom(field.getType())) {
                try {
                    return (ReloadInstance) field.get(overlay);
                } catch (IllegalAccessException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
