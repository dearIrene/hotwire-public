package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.registration.Registration;
import java.lang.ref.WeakReference;
import java.util.List;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.jspecify.annotations.Nullable;

final class RenderLayerAddition implements Registration {
    private final WeakReference<List<RenderLayer<?, ?>>> layers;
    private final WeakReference<RenderLayer<?, ?>> before;
    private final WeakReference<RenderLayer<?, ?>> layer;
    private final String name;

    private @Nullable RenderLayer<?, ?> out;

    RenderLayerAddition(List<RenderLayer<?, ?>> layers, @Nullable RenderLayer<?, ?> before, RenderLayer<?, ?> layer) {
        this.layers = new WeakReference<>(layers);
        this.before = new WeakReference<>(before);
        this.layer = new WeakReference<>(layer);
        this.name = layer.getClass().getName();
    }

    @Override
    public String getKind() {
        return "render layer";
    }

    @Override
    public String describe() {
        return this.name;
    }

    @Override
    public void remove() {
        List<RenderLayer<?, ?>> list = this.layers.get();
        RenderLayer<?, ?> mine = this.layer.get();
        int at = list == null || mine == null ? -1 : indexOf(list, mine);
        if (list != null && at >= 0) {
            list.remove(at);
            this.out = mine;
        }
    }

    @Override
    public void restore() {
        List<RenderLayer<?, ?>> list = this.layers.get();
        RenderLayer<?, ?> mine = this.out == null ? this.layer.get() : this.out;
        this.out = null;
        if (list == null || mine == null || indexOf(list, mine) >= 0) {
            return;
        }
        RenderLayer<?, ?> lead = this.before.get();
        int at = lead == null ? -1 : indexOf(list, lead);
        list.add(at + 1, mine);
    }

    @Override
    public boolean isInGame() {
        List<RenderLayer<?, ?>> list = this.layers.get();
        RenderLayer<?, ?> mine = this.layer.get();
        return list != null && mine != null && indexOf(list, mine) >= 0;
    }

    @SuppressWarnings("ReferenceEquality")
    static int indexOf(List<RenderLayer<?, ?>> layers, RenderLayer<?, ?> layer) {
        for (int i = 0; i < layers.size(); i++) {
            if (layers.get(i) == layer) {
                return i;
            }
        }
        return -1;
    }
}
