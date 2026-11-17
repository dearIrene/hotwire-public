package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.version.FeatureBands;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.registration.Owners;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

public final class OffFeatures {
    private OffFeatures() {
    }

    public static boolean isOff(PlacedFeature placed) {
        if (!Tripwire.isAnyWatched()) {
            return false;
        }
        if (FeatureBands.hasOffFeature(placed)) {
            return true;
        }
        for (PlacementModifier modifier : placed.placement()) {
            if (Owners.isOfOffMod(modifier.getClass())) {
                return true;
            }
        }
        return false;
    }
}
