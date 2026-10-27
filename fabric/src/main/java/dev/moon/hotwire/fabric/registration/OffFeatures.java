package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.Tripwire;
//? if <26.3 {
/*import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
*///?}
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

public final class OffFeatures {
    private OffFeatures() {
    }

    public static boolean isOff(PlacedFeature placed) {
        if (!Tripwire.isAnyWatched()) {
            return false;
        }
        //? if >=26.3 {
        if (isOfOffMod(placed.feature().value().getClass())) {
            return true;
        }
        //?} else {
        /*ConfiguredFeature<?, ?> configured = placed.feature().value();
        if (isOfOffMod(configured.feature().getClass()) || isOfOffMod(configured.config().getClass())) {
            return true;
        }
        *///?}
        for (PlacementModifier modifier : placed.placement()) {
            if (isOfOffMod(modifier.getClass())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOfOffMod(Class<?> type) {
        String mod = GameRegistrations.findModOf(type);
        return mod != null && Tripwire.isWatched(mod);
    }
}
