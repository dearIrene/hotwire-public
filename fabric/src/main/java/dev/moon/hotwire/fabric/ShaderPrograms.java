package dev.moon.hotwire.fabric;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
//? if >=1.21.5 {
import dev.moon.hotwire.config.Flags;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import net.minecraft.util.Unit;
//?} else if >=1.21.2 {
/*import dev.moon.hotwire.config.Flags;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.ShaderProgram;
import net.minecraft.client.renderer.ShaderProgramConfig;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import net.minecraft.util.Unit;
*///?}
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ShaderPrograms {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderPrograms.class);

    private static boolean madeThisSwitch;

    private static @Nullable Boolean changedThisSwitch;

    //? if >=1.21.5 {
    private static final Set<String> GAME_SOURCES = nameAll(ShaderManager.class, ShaderDefines.class,
            RenderPipelines.class);
    //?} else if >=1.21.2 {
    /*private static final Set<String> GAME_SOURCES = nameAll(ShaderManager.class, ShaderDefines.class,
            ShaderProgram.class, CompiledShaderProgram.class, CoreShaders.class, ShaderProgramConfig.class);
    *///?} else {
    /*private static final Set<String> GAME_SOURCES = nameAll();
    *///?}

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

    private static Set<String> nameAll(Class<?>... types) {
        return Arrays.stream(types).map(type -> Type.getInternalName(type))
                .collect(Collectors.toUnmodifiableSet());
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
        //? if >=1.21.2 {
        if (!Flags.SHADERS_AGAIN) {
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
