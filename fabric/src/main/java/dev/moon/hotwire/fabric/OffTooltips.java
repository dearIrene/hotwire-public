package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.add.LoaderModList;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
//? if >=26.1 {
import net.fabricmc.fabric.api.client.rendering.v1.ClientTooltipComponentCallback;
//?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.TooltipComponentCallback;
*///?}
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.jspecify.annotations.Nullable;

final class OffTooltips {
    private OffTooltips() {
    }

    static void register() {
        //? if >=26.1 {
        ClientTooltipComponentCallback.EVENT.register(OffTooltips::findFor);
        //?} else {
        /*TooltipComponentCallback.EVENT.register(OffTooltips::findFor);
        *///?}
    }

    private static @Nullable ClientTooltipComponent findFor(TooltipComponent data) {
        String mod = GameRegistrations.findModOf(data.getClass());
        return mod != null && LoaderModList.getHidden().contains(mod)
                ? ClientTooltipComponent.create(FormattedCharSequence.EMPTY) : null;
    }
}
