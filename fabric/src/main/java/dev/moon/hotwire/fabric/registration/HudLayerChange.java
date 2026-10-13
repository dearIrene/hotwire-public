package dev.moon.hotwire.fabric.registration;

//? if >=1.21.2 && <1.21.6 {
/*import dev.moon.hotwire.registration.Registration;
import java.util.List;
import net.minecraft.client.gui.LayeredDraw;
import org.jspecify.annotations.Nullable;
*///?}

final class HudLayerChange
        //? if >=1.21.2 && <1.21.6 {
        /*implements Registration
        *///?}
{
    //? if >=1.21.2 && <1.21.6 {
    /*private final List<LayeredDraw.Layer> layers;
    private final LayeredDraw.@Nullable Layer before;
    private final LayeredDraw.@Nullable Layer removed;
    private final LayeredDraw.@Nullable Layer added;
    private final Object id;

    HudLayerChange(List<LayeredDraw.Layer> layers, LayeredDraw.@Nullable Layer before,
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
    *///?}
}
