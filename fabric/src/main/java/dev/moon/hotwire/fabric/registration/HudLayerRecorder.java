package dev.moon.hotwire.fabric.registration;

//? if >=1.21.2 && <1.21.6 {
/*import dev.moon.hotwire.fabric.mixin.core.fabric.client.rendering.LayeredDrawerWrapperImplAccessor;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.fabricmc.fabric.impl.client.rendering.SubLayer;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;
*///?}

public final class HudLayerRecorder {
    //? if >=1.21.2 && <1.21.6 {
    /*// A layer and where it sits: its list, and the layer before it there (null when it is first).
    public record LayerPosition(List<LayeredDraw.Layer> layers, LayeredDraw.@Nullable Layer before,
            LayeredDraw.Layer layer) {
    }

    private HudLayerRecorder() {
    }

    @SuppressWarnings("ReferenceEquality") // the same layer, not an equal one
    public static void onAdded(LayeredDraw base, IdentifiedLayer layer) {
        LayerPosition position = find(base, layer.id());
        if (position != null && position.layer() == layer) {
            record(layer, new HudLayerChange(position.layers(), position.before(), null, layer, layer.id()));
        }
    }

    @SuppressWarnings("ReferenceEquality") // the same layer, not an equal one
    public static void onReplaced(@Nullable LayerPosition old) {
        if (old == null) {
            return;
        }
        LayeredDraw.Layer now = findIn(old.layers(), old.before());
        if (now != null && now != old.layer()) {
            record(now, new HudLayerChange(old.layers(), old.before(), old.layer(), now, getIdOf(old.layer())));
        }
    }

    public static void onRemoved(@Nullable LayerPosition old) {
        if (old != null && HudLayerChange.indexOf(old.layers(), old.layer()) < 0) {
            record(old.layer(),
                    new HudLayerChange(old.layers(), old.before(), old.layer(), null, getIdOf(old.layer())));
        }
    }

    public static @Nullable LayerPosition find(LayeredDraw drawer, ResourceLocation id) {
        List<LayeredDraw.Layer> layers = LayeredDrawerWrapperImplAccessor.hotwire$getLayers(drawer);
        LayeredDraw.Layer before = null;
        for (LayeredDraw.Layer layer : layers) {
            if (layer instanceof IdentifiedLayer identified && identified.id().equals(id)) {
                return new LayerPosition(layers, before, layer);
            }
            if (layer instanceof SubLayer sub) {
                LayerPosition inner = find(sub.delegate(), id);
                if (inner != null) {
                    return inner;
                }
            }
            before = layer;
        }
        return null;
    }

    private static LayeredDraw.@Nullable Layer findIn(
            List<LayeredDraw.Layer> layers, LayeredDraw.@Nullable Layer before) {
        int at = before == null ? 0 : HudLayerChange.indexOf(layers, before) + 1;
        if (at == 0 && before != null) {
            return null;
        }
        return at < layers.size() ? layers.get(at) : null;
    }

    private static Object getIdOf(LayeredDraw.Layer layer) {
        return layer instanceof IdentifiedLayer identified ? identified.id() : layer;
    }

    private static void record(LayeredDraw.Layer key, HudLayerChange change) {
        if (RecorderSupport.isFirst(key)) {
            RecorderSupport.record(key, change);
        }
    }
    *///?}
}
