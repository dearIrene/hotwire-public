package dev.moon.hotwire.fabric.registration;

//? if >=1.21.6 {
import dev.moon.hotwire.fabric.mixin.core.client.gui.render.GuiRendererAccessor;
import dev.moon.hotwire.registration.Registration;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
//?}

final class PictureInPictureRegistration
        //? if >=1.21.6 {
        implements Registration
        //?}
{
    //? if >=1.21.6 {
    private final Map<Class<?>, Object> registered;
    private final Object factory;
    private final PictureInPictureRenderer<?> renderer;

    PictureInPictureRegistration(Map<Class<?>, Object> registered, Object factory,
            PictureInPictureRenderer<?> renderer) {
        this.registered = registered;
        this.factory = factory;
        this.renderer = renderer;
    }

    @Override
    public String getKind() {
        return "picture-in-picture renderer";
    }

    @Override
    public String describe() {
        return this.renderer.getRenderStateClass().getSimpleName();
    }

    @Override
    public void remove() {
        Class<?> state = this.renderer.getRenderStateClass();
        getLive().remove(state, this.renderer);
        this.registered.remove(state, this.factory);
    }

    @Override
    public void restore() {
        Class<?> state = this.renderer.getRenderStateClass();
        getLive().putIfAbsent(state, this.renderer);
        this.registered.putIfAbsent(state, this.factory);
    }

    static Map<Class<?>, PictureInPictureRenderer<?>> getLive() {
        GuiRendererAccessor.Owner owner = (GuiRendererAccessor.Owner) Minecraft.getInstance().gameRenderer;
        return ((GuiRendererAccessor) owner.hotwire$getGuiRenderer()).hotwire$getPictureInPictureRenderers();
    }
    //?}
}
