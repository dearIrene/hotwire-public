package dev.moon.hotwire.fabric.mixin.core.client;

import dev.moon.hotwire.fabric.registration.KeyBindingRegistration;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Options.class)
public abstract class OptionsMixin {
    @Unique
    private KeyMapping @Nullable [] hotwire$keysWhileSaving;

    @Inject(method = "save", at = @At("HEAD"))
    private void hotwire$includeOffKeys(CallbackInfo ci) {
        OptionsAccessor access = (OptionsAccessor) this;
        this.hotwire$keysWhileSaving = access.hotwire$getKeyMappings();
        access.hotwire$setKeyMappings(KeyBindingRegistration.withRemoved(this.hotwire$keysWhileSaving));
    }

    @Inject(method = "save", at = @At("RETURN"))
    private void hotwire$restoreKeys(CallbackInfo ci) {
        KeyMapping[] before = this.hotwire$keysWhileSaving;
        if (before != null) {
            ((OptionsAccessor) this).hotwire$setKeyMappings(before);
            this.hotwire$keysWhileSaving = null;
        }
    }
}
