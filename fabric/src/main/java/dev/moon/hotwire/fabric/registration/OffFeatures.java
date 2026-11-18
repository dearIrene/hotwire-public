package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.mixin.core.world.level.levelgen.structure.StructureStartAccessor;
import dev.moon.hotwire.fabric.version.FeatureBands;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.registration.Owners;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

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

    public static boolean isOff(Structure structure) {
        return Tripwire.isAnyWatched() && Owners.isOfOffMod(structure.getClass());
    }

    public static boolean hasOffPiece(StructureStart start) {
        if (!Tripwire.isAnyWatched()) {
            return false;
        }
        for (Object part : ((StructureStartAccessor) (Object) start).hotwire$getParts()) {
            if (Owners.isOfOffMod(part.getClass())) {
                return true;
            }
            if (part instanceof PoolElementStructurePiece pooled
                    && Owners.isOfOffMod(pooled.getElement().getClass())) {
                return true;
            }
        }
        return false;
    }
}
