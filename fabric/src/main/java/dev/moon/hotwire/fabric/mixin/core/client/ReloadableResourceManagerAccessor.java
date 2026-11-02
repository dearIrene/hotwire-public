package dev.moon.hotwire.fabric.mixin.core.client;

import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ReloadableResourceManager.class)
public interface ReloadableResourceManagerAccessor {
    @Accessor("resources")
    CloseableResourceManager hotwire$getResources();

    @Accessor("resources")
    void hotwire$setResources(CloseableResourceManager resources);
}
