package dev.moon.hotwire.fabric.mixin.core.client.resources.language;

import net.minecraft.client.resources.language.I18n;
//? if <26.2 {
/*import net.minecraft.locale.Language;
*///?}
import org.spongepowered.asm.mixin.Mixin;
//? if <26.2 {
/*import org.spongepowered.asm.mixin.gen.Invoker;
*///?}

@Mixin(I18n.class)
public interface I18nAccessor {
    //? if <26.2 {
    /*@Invoker("setLanguage")
    static void hotwire$setLanguage(Language language) {
        throw new AssertionError();
    }
    *///?}
}
