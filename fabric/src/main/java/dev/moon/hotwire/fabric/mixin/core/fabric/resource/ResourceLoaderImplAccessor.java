package dev.moon.hotwire.fabric.mixin.core.fabric.resource;

import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = {"net.fabricmc.fabric.impl.resource.ResourceLoaderImpl",
        "net.fabricmc.fabric.impl.resource.v1.ResourceLoaderImpl"}, remap = false)
public interface ResourceLoaderImplAccessor {
    @Accessor("addedReloaders")
    Map<Identifier, PreparableReloadListener> hotwire$getReloaders();
}
