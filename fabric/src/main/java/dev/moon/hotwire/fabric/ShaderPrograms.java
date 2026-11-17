package dev.moon.hotwire.fabric;

import dev.moon.hotwire.config.Flags;
import dev.moon.hotwire.fabric.version.ReloadBands;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import net.minecraft.util.Unit;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ShaderPrograms {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderPrograms.class);

    private static boolean madeThisSwitch;

    private static @Nullable Boolean changedThisSwitch;

    private static final Set<String> GAME_SOURCES = ReloadBands.SHADER_SOURCES.stream()
            .map(type -> Type.getInternalName(type)).collect(Collectors.toUnmodifiableSet());

    private static final List<String> COMPILE_WORDS = List.of("Pipeline", "Shader", "Program", "Glsl", "SPIRV",
            "Preprocessor", "VertexFormat", "Device", "GlStateManager");

    private ShaderPrograms() {
    }

    static boolean isChangedBy(Collection<String> patched) {
        boolean changed = patched.stream().anyMatch(ShaderPrograms::isCompiledFrom);
        changedThisSwitch = changed;
        return changed;
    }

    private static boolean isCompiledFrom(String name) {
        int nest = name.indexOf('$');
        if (GAME_SOURCES.contains(nest < 0 ? name : name.substring(0, nest))) {
            return true;
        }
        if (!name.startsWith("com/mojang/blaze3d/") && !name.startsWith("com/mojang/renderpearl/")) {
            return false;
        }
        String simple = name.substring(name.lastIndexOf('/') + 1);
        return name.contains("/shaders/") || COMPILE_WORDS.stream().anyMatch(simple::contains);
    }

    static void forget() {
        madeThisSwitch = false;
        changedThisSwitch = null;
    }

    static void compileAgain(Minecraft game) {
        if (madeThisSwitch || Boolean.FALSE.equals(changedThisSwitch)) {
            return;
        }
        madeThisSwitch = true;
        PreparableReloadListener shaders = ReloadBands.findShaderListener(game);
        if (shaders == null) {
            LOGGER.debug("[Hotwire] the shaders of {} are compiled again by the reload after the hold", game);
            return;
        }

        if (!Flags.SHADERS_AGAIN) {
            return;
        }
        long began = System.nanoTime();
        try {
            ReloadInstance compiled = SimpleReloadInstance.create(game.getResourceManager(),
                    List.of(shaders), Runnable::run, game,
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
    }
}
