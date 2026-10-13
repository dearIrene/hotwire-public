package dev.moon.hotwire.fabric.mixin.core.client.gui.render;

//? if >=1.21.6 {
import java.util.Map;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GuiRenderer.class)
public interface GuiRendererAccessor {
    @Accessor("pictureInPictureRenderers")
    Map<Class<?>, PictureInPictureRenderer<?>> hotwire$getPictureInPictureRenderers();

    @Mixin(GameRenderer.class)
    interface Owner {
        @Accessor("guiRenderer")
        GuiRenderer hotwire$getGuiRenderer();
    }
}
//?} else {
/*import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "hotwire.Absent", remap = false)
public interface GuiRendererAccessor {
    @Pseudo
    @Mixin(targets = "hotwire.Absent", remap = false)
    interface Owner {
    }
}
*///?}
