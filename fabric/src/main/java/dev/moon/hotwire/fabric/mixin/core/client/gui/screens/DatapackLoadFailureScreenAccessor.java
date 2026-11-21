package dev.moon.hotwire.fabric.mixin.core.client.gui.screens;

import net.minecraft.client.gui.screens.DatapackLoadFailureScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DatapackLoadFailureScreen.class)
public interface DatapackLoadFailureScreenAccessor {
    @Accessor("cancelCallback")
    Runnable hotwire$getCancel();
}
