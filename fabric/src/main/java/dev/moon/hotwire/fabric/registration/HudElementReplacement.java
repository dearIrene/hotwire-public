package dev.moon.hotwire.fabric.registration;

//? if >=1.21.6 {
import dev.moon.hotwire.registration.Registration;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
//?}

final class HudElementReplacement
        //? if >=1.21.6 {
        implements Registration, Function<HudElement, HudElement>
        //?}
{
    //? if >=1.21.6 {
    private final Object id;
    private final Function<HudElement, HudElement> replacer;
    private volatile boolean isActive = true;

    HudElementReplacement(Object id, Function<HudElement, HudElement> replacer) {
        this.id = id;
        this.replacer = replacer;
    }

    @Override
    public HudElement apply(HudElement replaced) {
        return this.isActive ? this.replacer.apply(replaced) : replaced;
    }

    @Override
    public String getKind() {
        return "HUD element replacement";
    }

    @Override
    public String describe() {
        return this.id.toString();
    }

    @Override
    public void remove() {
        this.isActive = false;
    }

    @Override
    public void restore() {
        this.isActive = true;
    }
    //?}
}
