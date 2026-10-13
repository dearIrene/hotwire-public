package dev.moon.hotwire.fabric.registration;

//? if >=1.21.6 {
import dev.moon.hotwire.registration.Registration;
import java.util.Iterator;
import java.util.Objects;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.impl.client.rendering.hud.HudElementRegistryImpl;
import net.fabricmc.fabric.impl.client.rendering.hud.HudLayer;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
//?}

public final class HudElementAddition
        //? if >=1.21.6 {
        implements Registration
        //?}
{
    //? if >=1.21.6 {
    public enum Placement { FIRST, LAST, BEFORE, AFTER }

    private final Placement placement;
    private final @Nullable Identifier anchor;
    private final Identifier id;
    private final HudElement element;

    HudElementAddition(Placement placement, @Nullable Identifier anchor, Identifier id,
            HudElement element) {
        this.placement = placement;
        this.anchor = anchor;
        this.id = id;
        this.element = element;
    }

    @Override
    public String getKind() {
        return "HUD element";
    }

    @Override
    public String describe() {
        return this.id.toString();
    }

    @Override
    public void remove() {
        for (HudElementRegistryImpl.RootLayer root : HudElementRegistryImpl.ROOT_ELEMENTS.values()) {
            Iterator<HudLayer> layers = root.layers().iterator();
            while (layers.hasNext()) {
                if (layers.next().id().equals(this.id)) {
                    layers.remove();
                }
            }
        }
    }

    @Override
    public void restore() {
        if (this.isPresent()) {
            return;
        }
        switch (this.placement) {
            case FIRST -> HudElementRegistryImpl.addFirst(this.id, this.element);
            case LAST -> HudElementRegistryImpl.addLast(this.id, this.element);
            case BEFORE -> HudElementRegistryImpl.attachElementBefore(Objects.requireNonNull(this.anchor), this.id,
                    this.element);
            case AFTER -> HudElementRegistryImpl.attachElementAfter(Objects.requireNonNull(this.anchor), this.id,
                    this.element);
        }
    }

    private boolean isPresent() {
        for (HudElementRegistryImpl.RootLayer root : HudElementRegistryImpl.ROOT_ELEMENTS.values()) {
            for (HudLayer layer : root.layers()) {
                if (layer.id().equals(this.id)) {
                    return true;
                }
            }
        }
        return false;
    }
    //?}
}
