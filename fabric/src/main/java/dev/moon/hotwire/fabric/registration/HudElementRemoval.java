package dev.moon.hotwire.fabric.registration;

//? if >=1.21.6 {
import dev.moon.hotwire.registration.Registration;
import java.util.ListIterator;
import net.fabricmc.fabric.impl.client.rendering.hud.HudElementRegistryImpl;
import net.fabricmc.fabric.impl.client.rendering.hud.HudLayer;
import net.minecraft.resources.Identifier;
//?}

final class HudElementRemoval
        //? if >=1.21.6 {
        implements Registration
        //?}
{
    //? if >=1.21.6 {
    private final Identifier id;

    HudElementRemoval(Identifier id) {
        this.id = id;
    }

    @Override
    public String getKind() {
        return "HUD element removal";
    }

    @Override
    public String describe() {
        return this.id.toString();
    }

    @Override
    public void remove() {
        for (HudElementRegistryImpl.RootLayer root : HudElementRegistryImpl.ROOT_ELEMENTS.values()) {
            ListIterator<HudLayer> layers = root.layers().listIterator();
            while (layers.hasNext()) {
                HudLayer layer = layers.next();
                if (layer.id().equals(this.id) && layer.isRemoved()) {
                    layers.set(HudLayer.of(this.id, layer::element, false));
                }
            }
        }
    }

    @Override
    public void restore() {
        for (HudElementRegistryImpl.RootLayer root : HudElementRegistryImpl.ROOT_ELEMENTS.values()) {
            for (HudLayer layer : root.layers()) {
                if (layer.id().equals(this.id) && !layer.isRemoved()) {
                    HudElementRegistryImpl.removeElement(this.id);
                    return;
                }
            }
        }
    }
    //?}
}
