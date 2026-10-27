package dev.moon.hotwire.fabric.mixin.core.world.level.levelgen.placement;

import dev.moon.hotwire.fabric.registration.OffFeatures;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
//? if >=26.3 {
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
//?} else {
/*import net.minecraft.world.level.levelgen.placement.PlacementContext;
*///?}
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlacedFeature.class)
public abstract class PlacedFeatureMixin {
    //? if >=26.3 {
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void hotwire$skipOff(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin,
            CallbackInfoReturnable<Boolean> cir) {
    //?} else {
    /*@Inject(method = "placeWithContext", at = @At("HEAD"), cancellable = true)
    private void hotwire$skipOff(PlacementContext context, RandomSource random, BlockPos origin,
            CallbackInfoReturnable<Boolean> cir) {
    *///?}
        if (OffFeatures.isOff((PlacedFeature) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
