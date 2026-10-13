package dev.moon.hotwire.fabric.registration;

//? if >=1.21.6 {
import java.util.Map;
//?}
//? if >=26.1 {
import dev.moon.hotwire.fabric.add.OpenRegistries;
import dev.moon.hotwire.fabric.mixin.core.fabric.client.rendering.PictureInPictureRendererRegistryImplAccessor;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import org.jspecify.annotations.Nullable;
//?} elif >=1.21.6 {
/*import dev.moon.hotwire.fabric.add.OpenRegistries;
import dev.moon.hotwire.fabric.mixin.core.fabric.client.rendering.SpecialGuiElementRegistryImplAccessor;
import net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import org.jspecify.annotations.Nullable;
*///?}

public final class PictureInPictureRecorder {
    //? if >=26.1 {
    private static PictureInPictureRendererRegistry.@Nullable Context context;
    //?} elif >=1.21.6 {
    /*private static SpecialGuiElementRegistry.@Nullable Context context;
    *///?}

    private PictureInPictureRecorder() {
    }

    //? if >=26.1 {
    public static void onMade(PictureInPictureRendererRegistry.Context made) {
        context = made;
    }

    public static boolean registerLate(PictureInPictureRendererRegistry.Factory factory) {
        PictureInPictureRendererRegistry.Context made = context;
        if (!PictureInPictureRendererRegistryImplAccessor.hotwire$isFrozen() || !OpenRegistries.isOpen()
                || made == null) {
            return false;
        }
        PictureInPictureRenderer<?> renderer = factory.createRenderer(made);
        add(PictureInPictureRendererRegistryImplAccessor.hotwire$getRegisteredFactories(), factory, renderer);
        return true;
    }
    //?} elif >=1.21.6 {
    /*public static void onMade(SpecialGuiElementRegistry.Context made) {
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

    //? if >=1.21.6 {
    private static void add(Map<Class<?>, Object> registered, Object factory,
            PictureInPictureRenderer<?> renderer) {
        PictureInPictureRegistration registration = new PictureInPictureRegistration(registered, factory, renderer);
        registration.restore();
        RecorderSupport.record(renderer, registration);
    }
    //?}
}
