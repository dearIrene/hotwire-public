package dev.moon.hotwire.fabric.mixin.core.client.resources.language;

import java.util.function.Consumer;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.client.resources.language.LanguageManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LanguageManager.class)
public interface LanguageManagerAccessor {
    @Accessor("reloadCallback")
    Consumer<ClientLanguage> hotwire$getReloadCallback();
}
