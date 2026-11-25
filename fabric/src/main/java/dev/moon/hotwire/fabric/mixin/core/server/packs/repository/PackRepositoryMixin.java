package dev.moon.hotwire.fabric.mixin.core.server.packs.repository;

import dev.moon.hotwire.redefine.StartArguments;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;
import org.objectweb.asm.Type;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PackRepository.class)
public abstract class PackRepositoryMixin {
    @Group(name = "saveSources")
    @Inject(method = "<init>", at = @At("RETURN"))
    private void hotwire$saveSources(RepositorySource[] sources, CallbackInfo ci) {
        StartArguments.saveOf(this, Type.getInternalName(PackRepository.class) + ".<init>"
                + Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(RepositorySource[].class)), (Object) sources);
    }
}
