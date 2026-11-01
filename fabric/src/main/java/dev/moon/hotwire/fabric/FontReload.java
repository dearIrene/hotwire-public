package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.registration.Fields;
import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.redefine.Tripwire;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import net.minecraft.util.Unit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class FontReload {
    private static final Logger LOGGER = LoggerFactory.getLogger(FontReload.class);

    private FontReload() {
    }

    static void reloadFonts(String why, Collection<String> closing) {
        Minecraft game = Minecraft.getInstance();
        PreparableReloadListener fonts = findFontManager();
        if (fonts == null) {
            ResourceReload.run(why);
            return;
        }
        LOGGER.info("[Hotwire] {}; the game makes its fonts again", why);
        long began = System.nanoTime();
        try {
            Executor applying = task -> game.execute(() -> Tripwire.expect(closing, task));
            SimpleReloadInstance.create(game.getResourceManager(), List.of(fonts), ForkJoinPool.commonPool(),
                    applying, CompletableFuture.completedFuture(Unit.INSTANCE),
                            false).done().get(ResourceReload.WAIT.toMillis(),
                    TimeUnit.MILLISECONDS);
            LOGGER.info("[Hotwire] the fonts were made again in {} ms", (System.nanoTime() - began) / 1_000_000);
            Minecraft.getInstance().execute(ResourceReload::layOutChat);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("[Hotwire] the fonts could not be made again ({}); the game reloads its resources", why, e);
            ResourceReload.run(why);
        }
    }

    static @Nullable FontManager findFontManager() {
        Minecraft game = Minecraft.getInstance();
        for (FieldSlots.Slot field : Fields.getDeclared(Minecraft.class)) {
            try {
                if (FontManager.class.isAssignableFrom(field.getType()) && field.get(game) instanceof FontManager one) {
                    return one;
                }
            } catch (IllegalAccessException e) {
            }
        }
        return null;
    }
}
