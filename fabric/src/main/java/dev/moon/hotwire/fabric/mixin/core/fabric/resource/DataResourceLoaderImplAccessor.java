package dev.moon.hotwire.fabric.mixin.core.fabric.resource;

import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = {"net.fabricmc.fabric.impl.resource.DataResourceLoaderImpl",
        "net.fabricmc.fabric.impl.resource.v1.DataResourceLoaderImpl"}, remap = false)
public interface DataResourceLoaderImplAccessor {
    @Accessor("addedReloaderFactories")
    Map<Identifier, Function<HolderLookup.Provider, PreparableReloadListener>> hotwire$getFactories();
}
