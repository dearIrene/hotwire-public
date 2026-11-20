package dev.moon.hotwire.fabric.mixin.core.client.color.block;

import net.minecraft.client.color.block.BlockColors;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
//? if >=26.1 {
import java.util.List;
import java.util.Map;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.world.level.block.Block;
//?} else {
/*import net.minecraft.client.color.block.BlockColor;
import net.minecraft.core.IdMapper;
*///?}

@Mixin(BlockColors.class)
public interface BlockColorsAccessor {
    //? if >=26.1 {
    @Accessor("sources")
    Map<Block, List<BlockTintSource>> hotwire$getColors();
    //?} else {
    /*@Accessor("blockColors")
    IdMapper<BlockColor> hotwire$getColors();
    *///?}
}
