package dev.moon.hotwire.fabric.mixin.core.resources;

import dev.moon.hotwire.engine.DataPackFaults;
import java.util.Map;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(RegistryDataLoader.class)
public abstract class RegistryDataLoaderMixin {
    @ModifyVariable(method = "logErrors", at = @At("HEAD"), argsOnly = true)
    private static Map<ResourceKey<?>, Exception> hotwire$noteErrors(Map<ResourceKey<?>, Exception> errors) {
        DataPackFaults.note(errors.values());
        return errors;
    }
}
