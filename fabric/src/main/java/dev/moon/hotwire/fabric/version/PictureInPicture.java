package dev.moon.hotwire.fabric.version;

//? if >=1.21.6 {
import dev.moon.hotwire.fabric.add.OpenRegistries;
import dev.moon.hotwire.fabric.mixin.core.client.gui.render.GuiRendererAccessor;
import dev.moon.hotwire.registration.RecorderSupport;
import dev.moon.hotwire.registration.Registration;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import org.jspecify.annotations.Nullable;
//? if >=26.1 {
import dev.moon.hotwire.fabric.mixin.core.fabric.client.rendering.PictureInPictureRendererRegistryImplAccessor;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
//?} else {
/*import dev.moon.hotwire.fabric.mixin.core.fabric.client.rendering.SpecialGuiElementRegistryImplAccessor;
import net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry;
*///?}

public final class PictureInPicture {
    //? if >=26.1 {
    private static PictureInPictureRendererRegistry.@Nullable Context context;

    public static void onMade(PictureInPictureRendererRegistry.Context made) {
        context = made;
    }

    public static boolean registerLate(PictureInPictureRendererRegistry.Factory factory) {
        PictureInPictureRendererRegistry.Context seen = context;
        //? if >=26.2 {
        PictureInPictureRendererRegistry.Context made = seen != null ? seen : Minecraft::getInstance;
        //?} else {
        /*PictureInPictureRendererRegistry.Context made = seen;
        if (made == null) {
            return false;
        }
        *///?}
        if (!PictureInPictureRendererRegistryImplAccessor.hotwire$isFrozen() || !OpenRegistries.isOpen()) {
            return false;
        }
        PictureInPictureRenderer<?> renderer = factory.createRenderer(made);
        add(PictureInPictureRendererRegistryImplAccessor.hotwire$getRegisteredFactories(), factory, renderer);
        return true;
    }
    //?} else {
    /*private static SpecialGuiElementRegistry.@Nullable Context context;

    public static void onMade(SpecialGuiElementRegistry.Context made) {
        context = made;
    }

    public static boolean registerLate(SpecialGuiElementRegistry.Factory factory) {
        SpecialGuiElementRegistry.Context made = context;
        if (!SpecialGuiElementRegistryImplAccessor.hotwire$isFrozen() || !OpenRegistries.isOpen() || made == null) {
            return false;
        }
        PictureInPictureRenderer<?> renderer = factory.createSpecialRenderer(made);
        add(SpecialGuiElementRegistryImplAccessor.hotwire$getRegisteredFactories(), factory, renderer);
        return true;
    }
    *///?}

    private PictureInPicture() {
    }

    private static void add(Map<Class<?>, Object> registered, Object factory,
            PictureInPictureRenderer<?> renderer) {
        LateRenderer registration = new LateRenderer(registered, factory, renderer);
        registration.restore();
        RecorderSupport.record(renderer, registration);
    }

    private static final class LateRenderer implements Registration {
        private final Map<Class<?>, Object> registered;
        private final Object factory;
        private final PictureInPictureRenderer<?> renderer;

        LateRenderer(Map<Class<?>, Object> registered, Object factory,
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

        private static Map<Class<?>, PictureInPictureRenderer<?>> getLive() {
            GuiRendererAccessor.Owner owner = (GuiRendererAccessor.Owner) Minecraft.getInstance().gameRenderer;
            return ((GuiRendererAccessor) owner.hotwire$getGuiRenderer()).hotwire$getPictureInPictureRenderers();
        }
    }
}
//?} else {
/*// Before 1.21.6 the game has no picture-in-picture renderers.
public final class PictureInPicture {
    private PictureInPicture() {
    }
}
*///?}
