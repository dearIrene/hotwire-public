package dev.moon.hotwire.fabric.mixin.core.world.level.levelgen.structure;

import java.util.List;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(StructureStart.class)
public interface StructureStartAccessor {
    @Invoker("getPieces")
    List<?> hotwire$getParts();
}
