package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.fabric.registration.GameHold;
import dev.moon.hotwire.jar.ModManifest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.client.Minecraft;

final class AddStart {
    private AddStart() {
    }

    static CompletableFuture<Void> queue(List<String> ids, List<String> grew, CountDownLatch backedOut,
            ModManifest.Environment side, IntSupplier catchUp) {
        List<Runnable> starts = new ArrayList<>();
        starts.addAll(collectEntrypoints(ids, "main", ModInitializer.class, ModInitializer::onInitialize));
        if (side == ModManifest.Environment.CLIENT) {
            starts.addAll(collectEntrypoints(ids, "client", ClientModInitializer.class,
                    ClientModInitializer::onInitializeClient));
        }
        CompletableFuture<Void> started = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            OpenRegistries registries = OpenRegistries.open();
            OpenOneConfig configs;
            try {
                configs = OpenOneConfig.open();
            } catch (RuntimeException | LinkageError e) {
                registries.close();
                started.completeExceptionally(e);
                GameHold.holdGameUntil(backedOut);
                return;
            }
            Throwable failed = null;
            try {
                starts.forEach(Runnable::run);
                configs.markStarted();

                registries.close();

                int notSetUp = catchUp.getAsInt();
                if (notSetUp > 0) {
                    failed = new IllegalStateException(notSetUp + " objects could not be given the mods' setup");
                }
            } catch (RuntimeException | LinkageError e) {
                failed = e;
            } finally {
                configs.close();
                registries.close();
                grew.addAll(registries.getGrown());
            }
            if (failed == null) {
                started.complete(null);
            } else {
                started.completeExceptionally(failed);
                GameHold.holdGameUntil(backedOut);
            }
        });
        return started;
    }

    private static <T> List<Runnable> collectEntrypoints(List<String> ids, String key, Class<T> type,
            Consumer<T> start) {
        List<Runnable> starts = new ArrayList<>();
        for (String id : ids) {
            for (EntrypointContainer<T> entry : FabricLoader.getInstance().getEntrypointContainers(key, type)) {
                if (entry.getProvider().getMetadata().getId().equals(id)) {
                    starts.add(() -> start.accept(entry.getEntrypoint()));
                }
            }
        }
        return starts;
    }
}
