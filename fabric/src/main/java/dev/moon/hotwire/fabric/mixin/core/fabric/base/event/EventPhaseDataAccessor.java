package dev.moon.hotwire.fabric.mixin.core.fabric.base.event;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.base.event.EventPhaseData", remap = false)
public interface EventPhaseDataAccessor {
    @Accessor("listeners")
    Object[] hotwire$getListeners();

    @Mutable
    @Accessor("listeners")
    void hotwire$setListeners(Object[] listeners);
}
