package dev.moon.hotwire.fabric.screen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//? if >=26.3 {
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuSurface;
import dev.moon.hotwire.fabric.ModViews;
import java.lang.reflect.Field;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
//?} else if >=26.2 {
/*import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.moon.hotwire.fabric.ModViews;
import java.lang.reflect.Field;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
*///?}

public final class HoldFrame {
    private static final Logger LOGGER = LoggerFactory.getLogger(HoldFrame.class);
    private static final long EVERY_NANOS = 33_000_000L;
    private static long drawnAt;
    private static boolean failed;

    private HoldFrame() {
    }

    public static void begin() {
        failed = false;
        drawnAt = System.nanoTime();
    }

    public static void drawIfDue() {
        if (failed || System.nanoTime() - drawnAt < EVERY_NANOS) {
            return;
        }
        drawnAt = System.nanoTime();
        try {
            draw();
        } catch (Exception | LinkageError e) {
            failed = true;
            LOGGER.info("[Hotwire] the screen is not drawn while the game is held: {}", e.toString());
        }
    }

    //? if >=26.2 {
    private static void draw() throws Exception {
        if (!(ModViews.openScreen() instanceof RootScreen screen)) {
            return;
        }
        Minecraft game = Minecraft.getInstance();
        GpuSurface surface = game.windowSurface();
        if (!surface.isAcquired()) {
            surface.acquireNextTexture();
        }
        GuiRenderer gui = (GuiRenderer) find(GameRenderer.class, GuiRenderer.class).get(game.gameRenderer);
        GuiRenderState state = (GuiRenderState) find(GuiRenderer.class, GuiRenderState.class).get(gui);
        state.reset();
        screen.drawInHold(new GuiGraphicsExtractor(game, state, -1, -1));
        gui.render();
        gui.endFrame();
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        surface.blitFromTexture(encoder, Objects.requireNonNull(game.gameRenderer.mainRenderTarget()
                .getColorTextureView()));
        encoder.submit();
        surface.present();
    }

    private static Field find(Class<?> holder, Class<?> type) throws NoSuchFieldException {
        for (Field field : holder.getDeclaredFields()) {
            if (field.getType() == type) {
                field.setAccessible(true);
                return field;
            }
        }
        throw new NoSuchFieldException(holder.getName() + " has no " + type.getName());
    }
    //?} else {
    /*private static void draw() {
    }
    *///?}
}
