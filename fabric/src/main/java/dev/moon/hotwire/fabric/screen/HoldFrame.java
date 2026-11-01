package dev.moon.hotwire.fabric.screen;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//? if >=26.3 {
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuSurface;
import dev.moon.hotwire.fabric.ModViews;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.jspecify.annotations.Nullable;
//?} else if >=26.2 {
/*import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.moon.hotwire.fabric.ModViews;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.jspecify.annotations.Nullable;
*///?} else if <1.21.2 {
/*import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.moon.hotwire.fabric.ModViews;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.glfw.GLFW;
*///?}

public final class HoldFrame {
    private static final Logger LOGGER = LoggerFactory.getLogger(HoldFrame.class);
    private static final long EVERY_NANOS = 16_000_000L;
    private static long drawnAt;
    private static boolean failed;
    //? if >=26.2 {
    private static @Nullable Boolean vulkan;
    //?}

    private HoldFrame() {
    }

    public static void begin() {
        failed = false;
        drawnAt = System.nanoTime();
    }

    public static void drawIfDue() {
        if (failed || System.nanoTime() - drawnAt < EVERY_NANOS || !Minecraft.getInstance().isSameThread()) {
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
        if (!(ModViews.openScreen() instanceof RootScreen screen) || isVulkan()) {
            return;
        }
        Minecraft game = Minecraft.getInstance();
        GuiRenderer gui = (GuiRenderer) find(GameRenderer.class, GuiRenderer.class).get(game.gameRenderer);
        GuiRenderState state = (GuiRenderState) find(GuiRenderer.class, GuiRenderState.class).get(gui);
        state.reset();
        screen.drawInHold(new GuiGraphicsExtractor(game, state, -1, -1));
        gui.render();
        gui.endFrame();

        GpuSurface surface = game.windowSurface();
        if (!surface.isAcquired()) {
            surface.acquireNextTexture();
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        surface.blitFromTexture(encoder, Objects.requireNonNull(game.gameRenderer.mainRenderTarget()
                .getColorTextureView()));
        encoder.submit();
        surface.present();
    }

    private static boolean isVulkan() {
        Boolean known = vulkan;
        if (known == null) {
            known = RenderSystem.getDevice().getDeviceInfo().backendName().toLowerCase(Locale.ROOT).contains("vulkan");
            vulkan = known;
        }
        return known;
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
    //?} else if <1.21.2 {
    /*private static void draw() {
        if (!(ModViews.openScreen() instanceof RootScreen screen)) {
            return;
        }
        Minecraft game = Minecraft.getInstance();
        Window window = game.getWindow();
        RenderTarget target = game.getMainRenderTarget();
        RenderSystem.clear(16640, Minecraft.ON_OSX);
        target.bindWrite(true);
        RenderSystem.clear(256, Minecraft.ON_OSX);
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0.0F, (float) (window.getWidth() / window.getGuiScale()),
                (float) (window.getHeight() / window.getGuiScale()), 0.0F, 1000.0F, 21000.0F), VertexSorting.ORTHOGRAPHIC_Z);
        Matrix4fStack view = RenderSystem.getModelViewStack();
        view.pushMatrix();
        view.translation(0.0F, 0.0F, -11000.0F);
        RenderSystem.applyModelViewMatrix();
        GuiGraphics graphics = new GuiGraphics(game, game.renderBuffers().bufferSource());
        screen.drawInHold(graphics);
        graphics.flush();
        view.popMatrix();
        RenderSystem.applyModelViewMatrix();
        target.unbindWrite();
        target.blitToScreen(window.getWidth(), window.getHeight());
        GLFW.glfwSwapBuffers(window.getWindow());
    }
    *///?} else {
    /*private static void draw() {
    }
    *///?}
}
