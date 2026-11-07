package dev.moon.hotwire.fabric;

//? if >=26.1 {
import dev.moon.hotwire.switching.CrashPolicy;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

public final class GuardedHudElement implements HudElement {
    private volatile @Nullable HudElement inner;

    private GuardedHudElement(HudElement inner) {
        this.inner = inner;
    }

    public static HudElement wrap(HudElement element) {
        return element instanceof GuardedHudElement ? element : new GuardedHudElement(element);
    }

    @Override
    @SuppressWarnings("checkstyle:IllegalCatch")
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        HudElement drawn = this.inner;
        if (drawn == null) {
            return;
        }
        try {
            drawn.extractRenderState(graphics, delta);
        } catch (RuntimeException | Error e) {
            if (!CrashGuard.handle(e, CrashPolicy.Site.FRAME)) {
                throw e;
            }
        }
    }
}
//?} else if >=1.21.6 {
/*import dev.moon.hotwire.switching.CrashPolicy;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import org.jspecify.annotations.Nullable;

public final class GuardedHudElement implements HudElement {
    private volatile @Nullable HudElement inner;

    private GuardedHudElement(HudElement inner) {
        this.inner = inner;
    }

    public static HudElement wrap(HudElement element) {
        return element instanceof GuardedHudElement ? element : new GuardedHudElement(element);
    }

    @Override
    @SuppressWarnings("checkstyle:IllegalCatch") // the crash net sees every throw of the element; CrashPolicy decides
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        HudElement drawn = this.inner;
        if (drawn == null) {
            return;
        }
        try {
            drawn.render(graphics, delta);
        } catch (RuntimeException | Error e) {
            if (!CrashGuard.handle(e, CrashPolicy.Site.FRAME)) {
                throw e;
            }
        }
    }
}
*///?} else {
/*// Before 1.21.6 Fabric has no HUD element registry: a HUD throw is the frame's (CrashGuard).
public final class GuardedHudElement {
    private GuardedHudElement() {
    }
}
*///?}
