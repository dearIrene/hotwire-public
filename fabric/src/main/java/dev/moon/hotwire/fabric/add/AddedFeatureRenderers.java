package dev.moon.hotwire.fabric.add;

//? if >=26.2 {
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//?}

public final class AddedFeatureRenderers {
    //? if >=26.2 {
    private static final Logger LOGGER = LoggerFactory.getLogger(AddedFeatureRenderers.class);
    private static final String REGISTRY = "net.fabricmc.fabric.impl.client.rendering.FeatureRendererRegistryImpl";
    //?}

    private AddedFeatureRenderers() {
    }

    //? if >=26.2 {
    public static @Nullable FeatureRenderer<?> make(Object type) {
        try {
            Field list = Class.forName(REGISTRY).getDeclaredField("featureRenderers");
            list.setAccessible(true);
            for (Object entry : (List<?>) list.get(null)) {
                if (entry == null) {
                    continue;
                }
                Method ofType = entry.getClass().getDeclaredMethod("type");
                ofType.setAccessible(true);
                if (type.equals(ofType.invoke(entry))) {
                    Method renderer = entry.getClass().getDeclaredMethod("renderer");
                    renderer.setAccessible(true);
                    return (FeatureRenderer<?>) ((Supplier<?>) renderer.invoke(entry)).get();
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("[Hotwire] Fabric's feature renderers could not be read for {}: {}", type, e.toString());
        }
        return null;
    }
    //?}
}
