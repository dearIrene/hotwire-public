package dev.moon.hotwire.fabric;

import java.util.Collection;
//? if >=1.21.2 {
import java.util.List;
import java.util.concurrent.CompletableFuture;
//?}
import net.minecraft.client.Minecraft;
//? if >=1.21.2 {
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import net.minecraft.util.Unit;
//?}
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ShaderPrograms {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderPrograms.class);

    private static boolean madeThisSwitch;

    private ShaderPrograms() {
    }

    static boolean isChangedBy(Collection<String> patched) {
        return patched.stream().anyMatch(name -> name.startsWith("com/mojang/blaze3d/")
                || name.startsWith("net/minecraft/client/renderer/Shader")
                || name.startsWith("net/minecraft/client/renderer/RenderPipelines"));
    }

    static void forget() {
        madeThisSwitch = false;
    }

    static void compileAgain(Minecraft game) {
        if (madeThisSwitch) {
            return;
        }
        madeThisSwitch = true;
        //? if >=1.21.2 {
        if (!Boolean.parseBoolean(System.getProperty("hotwire.shadersAgain", "true"))) {
            return;
        }
        long began = System.nanoTime();
        try {
            ReloadInstance compiled = SimpleReloadInstance.create(game.getResourceManager(),
                    List.of(game.getShaderManager()), Runnable::run, game,
                    CompletableFuture.completedFuture(Unit.INSTANCE), false);
            ResourceReload.awaitWhilePaused(game, compiled::isDone);
            Throwable failure = compiled.isDone() ? ReloadFailures.findFailure(compiled) : null;
            if (failure != null || !compiled.isDone()) {
                LOGGER.warn("[Hotwire] the game's shaders were not compiled again under the hold; the reload after"
                        + " it compiles them: {}", failure == null ? "it did not end" : failure.toString());
                return;
            }
            LOGGER.info("[Hotwire] the game's shaders were compiled again without the off mods in {} ms",
                    (System.nanoTime() - began) / 1_000_000);
        } catch (RuntimeException e) {
            LOGGER.warn("[Hotwire] the game's shaders were not compiled again under the hold; the reload after it"
                    + " compiles them", e);
        }
        //?} else {
        /*// shortcut: 1.21.1 compiles its shaders in the game renderer's own listener; the whole reload does it.
        LOGGER.debug("[Hotwire] the shaders of {} are compiled again by the reload after the hold", game);
        *///?}
    }
}
