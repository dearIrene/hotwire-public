package dev.moon.hotwire.fabric.registration;

import java.util.List;
import net.minecraft.client.renderer.entity.layers.RenderLayer;

public final class RenderLayerRecorder {
    private RenderLayerRecorder() {
    }

    public static void onAdded(List<RenderLayer<?, ?>> layers, RenderLayer<?, ?> layer) {
        if (RecorderSupport.isFirst(layer)) {
            int at = RenderLayerAddition.indexOf(layers, layer);
            RecorderSupport.record(layer, new RenderLayerAddition(layers, at > 0 ? layers.get(at - 1) : null, layer));
        }
    }
}
