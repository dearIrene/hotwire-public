package dev.moon.hotwire.fabric.registration;

//? if >=26.3 {
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
//?} else if >=1.21.5 {
/*import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
*///?}
import java.util.List;
//? if >=1.21.4 {
import net.minecraft.client.color.item.ItemTintSources;
//?}
import net.minecraft.client.model.geom.ModelLayers;
//? if >=1.21.5 {
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.item.ItemModels;
import net.minecraft.client.renderer.item.properties.conditional.ConditionalItemModelProperties;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperties;
import net.minecraft.client.renderer.item.properties.select.SelectItemModelProperties;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
//?} else if >=1.21.4 {
/*import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.client.renderer.ShaderProgram;
import net.minecraft.client.renderer.item.ItemModels;
import net.minecraft.client.renderer.item.properties.conditional.ConditionalItemModelProperties;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperties;
import net.minecraft.client.renderer.item.properties.select.SelectItemModelProperties;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.resources.FileToIdConverter;
*///?}
import net.minecraft.client.renderer.texture.atlas.SpriteSources;
import net.minecraft.server.packs.resources.ResourceManager;

final class ReloadedListHolders {
    //? if >=26.3 {
    static final List<Class<?>> HOLDERS = List.of(ModelLayers.class, RenderPipelines.class, ItemModels.class,
            ConditionalItemModelProperties.class, RangeSelectItemModelProperties.class, SelectItemModelProperties.class,
            ItemTintSources.class, SpecialModelRenderers.class, SpriteSources.class);

    static boolean canBeMade(Object made, ResourceManager resources) {
        if (made instanceof RenderPipeline pipeline) {
            return pipeline.getShaders().entrySet().stream().allMatch(shader -> resources.getResource(
                    shader.getKey().idConverter().idToFile(shader.getValue())).isPresent());
        }
        return true;
    }
    //?} else if >=1.21.5 {
    /*static final List<Class<?>> HOLDERS = List.of(ModelLayers.class, RenderPipelines.class, ItemModels.class,
            ConditionalItemModelProperties.class, RangeSelectItemModelProperties.class, SelectItemModelProperties.class,
            ItemTintSources.class, SpecialModelRenderers.class, SpriteSources.class);

    static boolean canBeMade(Object made, ResourceManager resources) {
        if (made instanceof RenderPipeline pipeline) {
            return resources.getResource(ShaderType.VERTEX.idConverter().idToFile(pipeline.getVertexShader()))
                    .isPresent() && resources.getResource(ShaderType.FRAGMENT.idConverter().idToFile(
                    pipeline.getFragmentShader())).isPresent();
        }
        return true;
    }
    *///?} else if >=1.21.4 {
    /*static final List<Class<?>> HOLDERS = List.of(ModelLayers.class, CoreShaders.class, ItemModels.class,
            ConditionalItemModelProperties.class, RangeSelectItemModelProperties.class, SelectItemModelProperties.class,
            ItemTintSources.class, SpecialModelRenderers.class, SpriteSources.class);

    static boolean canBeMade(Object made, ResourceManager resources) {
        if (made instanceof ShaderProgram program) {
            return resources.getResource(FileToIdConverter.json("shaders").idToFile(program.configId())).isPresent();
        }
        return true;
    }
    *///?} else {
    /*static final List<Class<?>> HOLDERS = List.of(ModelLayers.class, SpriteSources.class);

    @SuppressWarnings("UnusedVariable") // the same question as later versions ask
    static boolean canBeMade(Object made, ResourceManager resources) {
        return true;
    }
    *///?}

    private ReloadedListHolders() {
    }
}
