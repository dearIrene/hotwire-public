package dev.moon.hotwire.fabric.version;

import dev.moon.hotwire.registration.Owners;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
//? if <26.3 {
/*import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
*///?}

public final class FeatureBands {
    private FeatureBands() {
    }

    public static boolean hasOffFeature(PlacedFeature placed) {
        //? if >=26.3 {
        return Owners.isOfOffMod(placed.feature().value().getClass());
        //?} else {
        /*ConfiguredFeature<?, ?> configured = placed.feature().value();
        return Owners.isOfOffMod(configured.feature().getClass())
                || Owners.isOfOffMod(configured.config().getClass());
        *///?}
    }
}
