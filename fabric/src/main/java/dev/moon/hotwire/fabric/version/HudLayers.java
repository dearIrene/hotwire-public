package dev.moon.hotwire.fabric.version;

//? if >=1.21.2 && <1.21.6 {
/*import dev.moon.hotwire.fabric.mixin.core.fabric.client.rendering.LayeredDrawerWrapperImplAccessor;
import dev.moon.hotwire.registration.RecorderSupport;
import dev.moon.hotwire.registration.Registration;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.fabricmc.fabric.impl.client.rendering.SubLayer;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;

public final class HudLayers {
    public record LayerPosition(List<LayeredDraw.Layer> layers, LayeredDraw.@Nullable Layer before,
            LayeredDraw.Layer layer) {
    }

    private HudLayers() {
    }

    @SuppressWarnings("ReferenceEquality") // the same layer, not an equal one
    public static void onAdded(LayeredDraw base, IdentifiedLayer layer) {
        LayerPosition position = find(base, layer.id());
        if (position != null && position.layer() == layer) {
            record(layer, new Change(position.layers(), position.before(), null, layer, layer.id()));
        }
    }

    @SuppressWarnings("ReferenceEquality") // the same layer, not an equal one
    public static void onReplaced(@Nullable LayerPosition old) {
        if (old == null) {
            return;
        }
        LayeredDraw.Layer now = findIn(old.layers(), old.before());
        if (now != null && now != old.layer()) {
            record(now, new Change(old.layers(), old.before(), old.layer(), now, getIdOf(old.layer())));
        }
    }

    public static void onRemoved(@Nullable LayerPosition old) {
        if (old != null && Change.indexOf(old.layers(), old.layer()) < 0) {
            record(old.layer(),
                    new Change(old.layers(), old.before(), old.layer(), null, getIdOf(old.layer())));
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
        int at = before == null ? 0 : Change.indexOf(layers, before) + 1;
        if (at == 0 && before != null) {
            return null;
        }
        return at < layers.size() ? layers.get(at) : null;
    }

    private static Object getIdOf(LayeredDraw.Layer layer) {
        return layer instanceof IdentifiedLayer identified ? identified.id() : layer;
    }

    private static void record(LayeredDraw.Layer key, Change change) {
        if (RecorderSupport.isFirst(key)) {
            RecorderSupport.record(key, change);
        }
    }

    private static final class Change implements Registration {
        private final List<LayeredDraw.Layer> layers;
        private final LayeredDraw.@Nullable Layer before;
        private final LayeredDraw.@Nullable Layer removed;
        private final LayeredDraw.@Nullable Layer added;
        private final Object id;

        Change(List<LayeredDraw.Layer> layers, LayeredDraw.@Nullable Layer before,
                LayeredDraw.@Nullable Layer removed, LayeredDraw.@Nullable Layer added, Object id) {
            this.layers = layers;
            this.before = before;
            this.removed = removed;
            this.added = added;
            this.id = id;
        }

        @Override
        public String getKind() {
            if (this.removed == null) {
                return "HUD layer";
            }
            return this.added == null ? "HUD layer removal" : "HUD layer replacement";
        }

        @Override
        public String describe() {
            return this.id.toString();
        }

        @Override
        public void remove() {
            this.swap(this.added, this.removed);
        }

        @Override
        public void restore() {
            this.swap(this.removed, this.added);
        }

        private void swap(LayeredDraw.@Nullable Layer out, LayeredDraw.@Nullable Layer in) {
            if (in != null && indexOf(this.layers, in) >= 0) {
                return;
            }
            int at = out == null ? -1 : indexOf(this.layers, out);
            if (at >= 0) {
                this.layers.remove(at);
            }
            if (in == null) {
                return;
            }
            this.layers.add(at >= 0 ? at : this.findPlace(), in);
        }

        private int findPlace() {
            if (this.before == null) {
                return 0;
            }
            int at = indexOf(this.layers, this.before);
            if (at < 0) {
                throw new IllegalStateException("The HUD layer that " + this.id + " sat after is gone.");
            }
            return at + 1;
        }

        @SuppressWarnings("ReferenceEquality") // the same layer, not an equal one
        static int indexOf(List<LayeredDraw.Layer> layers, LayeredDraw.Layer layer) {
            for (int i = 0; i < layers.size(); i++) {
                if (layers.get(i) == layer) {
                    return i;
                }
            }
            return -1;
        }
    }
}
*///?} else {
public final class HudLayers {
    private HudLayers() {
    }
}
//?}
