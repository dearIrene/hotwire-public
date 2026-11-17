package dev.moon.hotwire.fabric;

import dev.moon.hotwire.add.LoaderModList;
import dev.moon.hotwire.fabric.version.Spellings;
import dev.moon.hotwire.registration.Owners;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.jspecify.annotations.Nullable;

final class OffTooltips {
    private OffTooltips() {
    }

    static void register() {
        Spellings.registerTooltips(OffTooltips::findFor);
    }

    private static @Nullable ClientTooltipComponent findFor(TooltipComponent data) {
        String mod = Owners.findModOf(data.getClass());
        return mod != null && LoaderModList.isHidden(mod)
                ? ClientTooltipComponent.create(FormattedCharSequence.EMPTY) : null;
    }
}
