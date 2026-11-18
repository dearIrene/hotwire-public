package dev.moon.hotwire.fabric.mixin.core.server.level;

import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.2 {
import java.util.concurrent.atomic.AtomicInteger;
import org.spongepowered.asm.mixin.gen.Accessor;
//?}

@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    //? if >=1.21.2 {
    @Accessor("activeChunkWrites")
    AtomicInteger hotwire$getActiveChunkWrites();
    //?}
}
