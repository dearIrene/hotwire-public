package dev.moon.hotwire.fabric.mixin.core.world.level.chunk.storage;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.moon.hotwire.registration.OffModSaves;
//? if <1.21.4 {
/*import net.minecraft.nbt.CompoundTag;
*///?}
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
//? if >=1.21.4 {
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
//?} else {
/*import net.minecraft.world.level.chunk.storage.ChunkSerializer;
*///?}
import org.spongepowered.asm.mixin.Mixin;

//? if >=1.21.4 {
@Mixin(SerializableChunkData.class)
//?} else {
/*@Mixin(ChunkSerializer.class)
*///?}
public abstract class SerializableChunkDataMixin {
    //? if >=1.21.4 {
    @WrapMethod(method = "copyOf")
    private static SerializableChunkData hotwire$copyAsOn(ServerLevel level, ChunkAccess chunk,
            Operation<SerializableChunkData> original) {
        return OffModSaves.run(() -> original.call(level, chunk));
    }

    @WrapMethod(method = "read")
    private ProtoChunk hotwire$readAsOn(ServerLevel level, PoiManager poi, RegionStorageInfo storage, ChunkPos pos,
            Operation<ProtoChunk> original) {
        return OffModSaves.run(() -> original.call(level, poi, storage, pos));
    }
    //?} else {
    /*@WrapMethod(method = "write")
    private static CompoundTag hotwire$writeAsOn(ServerLevel level, ChunkAccess chunk,
            Operation<CompoundTag> original) {
        return OffModSaves.run(() -> original.call(level, chunk));
    }

    @WrapMethod(method = "read")
    private static ProtoChunk hotwire$readAsOn(ServerLevel level, PoiManager poi, RegionStorageInfo storage,
            ChunkPos pos, CompoundTag tag, Operation<ProtoChunk> original) {
        return OffModSaves.run(() -> original.call(level, poi, storage, pos, tag));
    }
    *///?}
}
