package dev.moon.hotwire.fabric.mixin.core.client.resources.language;

import dev.moon.hotwire.fabric.OffWords;
import java.util.List;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ClientLanguage.class)
public abstract class ClientLanguageMixin {
    @Group(name = "skipOffWords")
    @ModifyArg(method = "loadFrom", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/language/"
            + "ClientLanguage;appendFrom(Ljava/lang/String;Ljava/util/List;Ljava/util/Map;)V"), index = 1)
    private static List<Resource> hotwire$skipOffWords(List<Resource> stack) {
        return OffWords.leaveOutOff(stack);
    }
}
