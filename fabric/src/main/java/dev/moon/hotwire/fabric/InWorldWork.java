package dev.moon.hotwire.fabric;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;

final class InWorldWork {
    private static final Duration LOOK_DELAY = Duration.ofSeconds(20);

    private static final Duration FILL_DELAY = Duration.ofSeconds(60);

    private InWorldWork() {
    }

    static void lookAheadSoon(HotwireEngine engine) {
        CompletableFuture.delayedExecutor(LOOK_DELAY.toSeconds(), TimeUnit.SECONDS).execute(() -> {
            HotwireEngine.JvmParts parts = engine.findParts();
            if (parts != null && Minecraft.getInstance().level != null) {
                parts.runner().lookAheadForAll();
            }
        });
    }

    static void fillCacheSoon(HotwireEngine engine) {
        if (!engine.getSettings().isAutoFillOn()) {
            return;
        }
        CompletableFuture.delayedExecutor(FILL_DELAY.toSeconds(), TimeUnit.SECONDS).execute(() -> {
            HotwireEngine.JvmParts parts = engine.findParts();
            if (parts != null && Minecraft.getInstance().level != null) {
                parts.runner().getSwitcher().getFiller().fillLoadedSince();
            }
        });
    }
}
