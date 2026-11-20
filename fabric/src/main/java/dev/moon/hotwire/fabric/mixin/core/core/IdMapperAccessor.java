package dev.moon.hotwire.fabric.mixin.core.core;

import java.util.List;
import net.minecraft.core.IdMapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(IdMapper.class)
public interface IdMapperAccessor {
    @Accessor("idToT")
    List<Object> hotwire$getById();
}
