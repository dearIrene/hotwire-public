package dev.moon.hotwire.fabric.mixin.core.core;

import java.util.Map;
import net.minecraft.core.MappedRegistry;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MappedRegistry.class)
public interface MappedRegistryAccessor {
    @Accessor("frozen")
    boolean hotwire$isFrozen();

    @Accessor("frozen")
    void hotwire$setFrozen(boolean frozen);

    @Accessor("unregisteredIntrusiveHolders")
    @Nullable Map<Object, Object> hotwire$getUnregisteredIntrusiveHolders();

    @Accessor("unregisteredIntrusiveHolders")
    void hotwire$setUnregisteredIntrusiveHolders(@Nullable Map<Object, Object> holders);
}
