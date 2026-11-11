package dev.moon.hotwire.fabric.screen;

//? if >=1.21.9 {
import com.mojang.blaze3d.platform.cursor.CursorTypes;
//?}
//? if <1.21.2 {
/*import com.mojang.blaze3d.systems.RenderSystem;
*///?}
import dev.moon.hotwire.ui.Canvas;
import dev.moon.hotwire.ui.Colors;
//? if <1.21.6 {
/*import java.util.Arrays;
*///?}
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
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

    private static final Map<String, FormattedCharSequence> ORDERED = new HashMap<>();
    private static Language orderedFor = Language.getInstance();

    //? if >=26.1 {
    private final GuiGraphicsExtractor graphics;
    //?} else {
    /*private final GuiGraphics graphics;
    *///?}
    private final Font font;
    //? if >=1.21.6 {
    //?} else {
    /*// The scales pushed so far as one: x times scale plus shift. Before 1.21.6 the scissor takes no pose, so a clip
    private float scale = 1;
    private float shiftX;
    private float shiftY;
    private float[] scales = new float[0];
    *///?}
    private long making;

    //? if >=26.1 {
    public GuiCanvas(GuiGraphicsExtractor graphics, Font font) {
    //?} else {
    /*public GuiCanvas(GuiGraphics graphics, Font font) {
    *///?}
        this.graphics = graphics;
        this.font = font;
    }

    //? if >=1.21.9 {
    @Override
    public void requestCursor(Cursor cursor) {
        this.graphics.requestCursor(switch (cursor) {
            case RESIZE_EW -> CursorTypes.RESIZE_EW;
            case RESIZE_NS -> CursorTypes.RESIZE_NS;
            case RESIZE_ALL -> CursorTypes.RESIZE_ALL;
        });
    }
    //?}

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

    @SuppressWarnings("ReferenceEquality")
    @Override
    public void drawText(String text, int x, int y, int argb) {
        if (argb >>> 24 < 4 || text.isEmpty()) {
            return;
        }
        Language language = Language.getInstance();
        if (language != orderedFor || ORDERED.size() >= MOST_WIDTHS) {
            ORDERED.clear();
            orderedFor = language;
        }
        FormattedCharSequence ordered = ORDERED.computeIfAbsent(text,
                words -> language.getVisualOrder(FormattedText.of(words)));
        //? if >=26.1 {
        this.graphics.text(this.font, ordered, x, y, argb, false);
        //?} else {
        /*this.graphics.drawString(this.font, ordered, x, y, argb, false);
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

    private static final Map<String, Identifier> ICONS = new HashMap<>();

    @Override
    public void drawIcon(String name, int x, int y, int side, int argb) {
        Identifier texture = ICONS.computeIfAbsent(name, icon -> Identifier.fromNamespaceAndPath("hotwire",
                "textures/icon/" + icon + ".png"));
        //? if >=1.21.6 {
        this.graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, side, side, side, side, argb);
        //?} else if >=1.21.2 {
        /*this.graphics.blit(RenderType::guiTextured, texture, x, y, 0, 0, side, side, side, side, argb);
        *///?} else {
        /*RenderSystem.enableBlend();
        RenderSystem.setShaderColor((argb >> 16 & 0xFF) / 255f, (argb >> 8 & 0xFF) / 255f, (argb & 0xFF) / 255f,
                (argb >>> 24) / 255f);
        this.graphics.blit(texture, x, y, 0, 0, side, side, side, side);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.disableBlend();
        *///?}
    }

    @Override
    public boolean drawImage(Canvas.Image image, int x, int y, int width, int height, float alpha) {
        if (alpha <= 0) {
            return false;
        }
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        long began = System.nanoTime();
        boolean mayMake = this.making < MAKING_BUDGET_NANOS;
        Identifier texture = switch (image) {
            case ModIcons.Icon icon -> icon.findTexture((int) Math.round(width * scale), scale, mayMake);
            case Picture picture -> picture.findTexture((int) Math.round(width * scale),
                    (int) Math.round(height * scale), mayMake);
            default -> null;
        };
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
        //? if >=1.21.6 {
        this.graphics.enableScissor(x0, y0, x1, y1);
        //?} else {
        /*this.graphics.enableScissor(Math.round(x0 * this.scale + this.shiftX), Math.round(y0 * this.scale
                + this.shiftY), Math.round(x1 * this.scale + this.shiftX), Math.round(y1 * this.scale + this.shiftY));
        *///?}
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
        float[] pushed = Arrays.copyOf(this.scales, this.scales.length + 3);
        pushed[pushed.length - 3] = this.scale;
        pushed[pushed.length - 2] = this.shiftX;
        pushed[pushed.length - 1] = this.shiftY;
        this.scales = pushed;
        this.shiftX += this.scale * centerX * (1 - scale);
        this.shiftY += this.scale * centerY * (1 - scale);
        this.scale *= scale;
        *///?}
    }

    @Override
    public void pushShift(float dx, float dy) {
        //? if >=1.21.6 {
        this.graphics.pose().pushMatrix();
        this.graphics.pose().translate(dx, dy);
        //?} else {
        /*this.graphics.pose().pushPose();
        this.graphics.pose().translate(dx, dy, 0);
        *///?}
    }

    @Override
    public void popShift() {
        this.popScale();
    }

    @Override
    public void popScale() {
        //? if >=1.21.6 {
        this.graphics.pose().popMatrix();
        //?} else {
        /*this.graphics.pose().popPose();
        if (this.scales.length >= 3) {
            this.scale = this.scales[this.scales.length - 3];
            this.shiftX = this.scales[this.scales.length - 2];
            this.shiftY = this.scales[this.scales.length - 1];
            this.scales = Arrays.copyOf(this.scales, this.scales.length - 3);
        }
        *///?}
    }
}
