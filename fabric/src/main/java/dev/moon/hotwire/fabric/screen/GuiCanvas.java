package dev.moon.hotwire.fabric.screen;

//? if <1.21.2 {
/*import com.mojang.blaze3d.systems.RenderSystem;
*///?}
import dev.moon.hotwire.ui.Canvas;
import dev.moon.hotwire.ui.Colors;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.resources.Identifier;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
//? if >=1.21.6 {
import net.minecraft.client.renderer.RenderPipelines;
//?} else if >=1.21.2 {
/*import net.minecraft.client.renderer.RenderType;
*///?}

public final class GuiCanvas implements Canvas {
    private static final long MAKING_BUDGET_NANOS = 1_000_000;
    private static final int STRIP = 4;

    private static final int MOST_WIDTHS = 4096;
    private static final Map<String, Integer> WIDTHS = new HashMap<>();

    //? if >=26.1 {
    private final GuiGraphicsExtractor graphics;
    //?} else {
    /*private final GuiGraphics graphics;
    *///?}
    private final Font font;
    private long making;

    //? if >=26.1 {
    public GuiCanvas(GuiGraphicsExtractor graphics, Font font) {
    //?} else {
    /*public GuiCanvas(GuiGraphics graphics, Font font) {
    *///?}
        this.graphics = graphics;
        this.font = font;
    }

    @Override
    public void fill(int x0, int y0, int x1, int y1, int argb) {
        if (argb >>> 24 == 0) {
            return;
        }
        this.graphics.fill(x0, y0, x1, y1, argb);
    }

    @Override
    public void fillDown(int x0, int y0, int x1, int y1, int topArgb, int bottomArgb) {
        int height = y1 - y0;
        if (height <= 0 || x1 <= x0 || ((topArgb | bottomArgb) >>> 24) == 0) {
            return;
        }
        int strips = Math.max(1, Math.min(height, (height + STRIP - 1) / STRIP));
        for (int i = 0; i < strips; i++) {
            int from = y0 + height * i / strips;
            int to = y0 + height * (i + 1) / strips;
            float t = (i + 0.5f) / strips;
            this.fill(x0, from, x1, to, Colors.mix(topArgb, bottomArgb, t));
        }
    }

    @Override
    public void fillAcross(int x0, int y0, int x1, int y1, int leftArgb, int rightArgb) {
        int width = x1 - x0;
        if (width <= 0 || y1 <= y0 || ((leftArgb | rightArgb) >>> 24) == 0) {
            return;
        }
        int strips = Math.max(1, Math.min(width, (width + STRIP - 1) / STRIP));
        for (int i = 0; i < strips; i++) {
            int from = x0 + width * i / strips;
            int to = x0 + width * (i + 1) / strips;
            float t = (i + 0.5f) / strips;
            this.fill(from, y0, to, y1, Colors.mix(leftArgb, rightArgb, t));
        }
    }

    @Override
    public void drawText(String text, int x, int y, int argb) {
        if (argb >>> 24 < 4 || text.isEmpty()) {
            return;
        }
        //? if >=26.1 {
        this.graphics.text(this.font, text, x, y, argb, false);
        //?} else {
        /*this.graphics.drawString(this.font, text, x, y, argb, false);
        *///?}
    }

    @Override
    public int measureText(String text) {
        Integer known = WIDTHS.get(text);
        if (known != null) {
            return known;
        }
        if (WIDTHS.size() >= MOST_WIDTHS) {
            WIDTHS.clear();
        }
        int width = this.font.width(text);
        WIDTHS.put(text, width);
        return width;
    }

    @Override
    public String clip(String text, int width) {
        return this.font.plainSubstrByWidth(text, Math.max(0, width));
    }

    @Override
    public boolean drawImage(Canvas.Image image, int x, int y, int width, int height, float alpha) {
        if (!(image instanceof ModIcons.Icon icon) || alpha <= 0) {
            return false;
        }
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        long began = System.nanoTime();
        Identifier texture = icon.findTexture((int) Math.round(width * scale), scale,
                this.making < MAKING_BUDGET_NANOS);
        this.making += System.nanoTime() - began;
        if (texture == null) {
            return false;
        }
        //? if >=1.21.2 {
        int tint = (Math.round(Math.min(1, alpha) * 255) << 24) | 0xFFFFFF;
        //?}
        //? if >=1.21.6 {
        this.graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, width, height, width, height, tint);
        //?} else if >=1.21.2 {
        /*this.graphics.blit(RenderType::guiTextured, texture, x, y, 0, 0, width, height, width, height, tint);
        *///?} else {
        /*RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1, 1, 1, Math.min(1, alpha));
        this.graphics.blit(texture, x, y, 0, 0, width, height, width, height);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.disableBlend();
        *///?}
        return true;
    }

    @Override
    public void pushClip(int x0, int y0, int x1, int y1) {
        this.graphics.enableScissor(x0, y0, x1, y1);
    }

    @Override
    public void popClip() {
        this.graphics.disableScissor();
    }

    @Override
    public void pushScale(float scale, float centerX, float centerY) {
        //? if >=1.21.6 {
        this.graphics.pose().pushMatrix();
        this.graphics.pose().translate(centerX, centerY);
        this.graphics.pose().scale(scale, scale);
        this.graphics.pose().translate(-centerX, -centerY);
        //?} else {
        /*this.graphics.pose().pushPose();
        this.graphics.pose().translate(centerX, centerY, 0);
        this.graphics.pose().scale(scale, scale, 1);
        this.graphics.pose().translate(-centerX, -centerY, 0);
        *///?}
    }

    @Override
    public void popScale() {
        //? if >=1.21.6 {
        this.graphics.pose().popMatrix();
        //?} else {
        /*this.graphics.pose().popPose();
        *///?}
    }
}
