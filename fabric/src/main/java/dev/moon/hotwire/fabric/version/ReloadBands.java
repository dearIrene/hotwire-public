package dev.moon.hotwire.fabric.version;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.jspecify.annotations.Nullable;
//? if <26.1 {
/*import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.resources.model.ModelManager;
*///?}
//? if >=1.21.5 {
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.ShaderManager;
//?} else if >=1.21.2 {
/*import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.ShaderProgram;
import net.minecraft.client.renderer.ShaderProgramConfig;
*///?}

public final class ReloadBands {
    //? if >=26.1 {
    public static final List<Class<?>> HEAVY_LISTENERS = List.of(SoundManager.class, FontManager.class,
            LanguageManager.class, TextureManager.class);
    //?} else {
    /*public static final List<Class<?>> HEAVY_LISTENERS = List.of(ModelManager.class, SoundManager.class,
            FontManager.class, LanguageManager.class, TextureManager.class, LevelRenderer.class);
    *///?}

    //? if >=1.21.5 {
    public static final List<Class<?>> SHADER_SOURCES = List.of(ShaderManager.class, ShaderDefines.class,
            RenderPipelines.class);
    public static final boolean SHADERS_READ_LATE = true;
    //?} else if >=1.21.2 {
    /*public static final List<Class<?>> SHADER_SOURCES = List.of(ShaderManager.class, ShaderDefines.class,
            ShaderProgram.class, CompiledShaderProgram.class, CoreShaders.class, ShaderProgramConfig.class);
    public static final boolean SHADERS_READ_LATE = false;
    *///?} else {
    /*public static final List<Class<?>> SHADER_SOURCES = List.of();
    public static final boolean SHADERS_READ_LATE = false;
    *///?}

    private ReloadBands() {
    }

    public static @Nullable PreparableReloadListener findShaderListener(Minecraft game) {
        //? if >=1.21.2 {
        return game.getShaderManager();
        //?} else {
        /*return null;
        *///?}
    }
}
