package dev.moon.hotwire.fabric.mixin.core.client.multiplayer;

//? if >=26.1 {
import dev.moon.hotwire.fabric.add.LoadStatusViews;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.server.level.progress.ChunkLoadStatusView;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.minecraft.client.multiplayer.LevelLoadTracker")
public abstract class LevelLoadTrackerMixin {
    //? if >=26.1 {
    @Inject(method = "setServerChunkStatusView", at = @At("RETURN"), require = 0)
    private void hotwire$noteView(ChunkLoadStatusView view, CallbackInfo ci) {
        if (view != null) {
            LoadStatusViews.note((LevelLoadTracker) (Object) this);
        }
    }
    //?}
}
