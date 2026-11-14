package dev.moon.hotwire.fabric.screen;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HoldFrameDiag {
    private static final Logger LOGGER = LoggerFactory.getLogger(HoldFrameDiag.class);
    private static final boolean ON = Boolean.getBoolean("hotwire.diagFrames")
            || HoldFrameDiag.class.getResource("/hotwire-diag-frames") != null;
    private static final int CELLS = 16;

    private static final int DEEP = 5;
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static int holds;
    private static int frame;
    private static boolean told;
    private static int[] last = new int[0];

    private HoldFrameDiag() {
    }

    public static boolean isOn() {
        return ON;
    }

    static void begin(String at) {
        if (!ON) {
            return;
        }
        holds++;
        frame = 0;
        LOGGER.info("[Hotwire] diag: hold {} begins; the game's thread was at {}", holds, at);
    }

    static void skip(String why) {
        if (ON && frame == 0) {
            frame = DEEP;
            LOGGER.info("[Hotwire] diag: hold {} draws no frames: {}", holds, why);
        }
    }

    static void measure(String stage, String said) {
        if (!ON || frame >= DEEP || (stage.equals("before") && frame > 0)) {
            return;
        }
        try {
            read(stage, said);
        } catch (RuntimeException | LinkageError e) {
            frame = DEEP;
            LOGGER.info("[Hotwire] diag: the held frame could not be read back: {}", e.toString());
        }
    }

    static void endFrame() {
        if (ON && frame < DEEP) {
            frame++;
        }
    }

    private static void read(String stage, String said) {
        Minecraft game = Minecraft.getInstance();
        int width = game.getWindow().getWidth();
        int height = game.getWindow().getHeight();
        if (width <= 0 || height <= 0) {
            LOGGER.info("[Hotwire] diag: hold {} frame {} {}: the window is {}x{}", holds, frame, stage, width, height);
            return;
        }
        if (!told) {
            told = true;
            LOGGER.info("[Hotwire] diag: OpenGL {} | {} | {}; window {}x{}, screen {}x{}", GL11.glGetString(
                    GL11.GL_VENDOR), GL11.glGetString(GL11.GL_RENDERER), GL11.glGetString(GL11.GL_VERSION), width,
                    height, game.getWindow().getScreenWidth(), game.getWindow().getScreenHeight());
        }
        int errorBefore = GL11.glGetError();
        int drawBefore = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readBefore = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int packBefore = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            int bufferBefore = GL11.glGetInteger(GL11.GL_READ_BUFFER);
            GL11.glReadBuffer(stage.equals("drawn") ? GL11.GL_BACK : GL11.GL_FRONT);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            GL11.glReadBuffer(bufferBefore);
            int error = GL11.glGetError();
            long[] sums = new long[CELLS * CELLS];
            int[] counts = new int[sums.length];

            for (int y = 0; y < height; y += 4) {
                int row = (CELLS - 1 - y * CELLS / height) * CELLS;
                for (int x = 0; x < width; x += 4) {
                    int at = (y * width + x) * 4;
                    int cell = row + x * CELLS / width;
                    sums[cell] += (pixels.get(at) & 0xFF) + (pixels.get(at + 1) & 0xFF) + (pixels.get(at + 2) & 0xFF);
                    counts[cell]++;
                }
            }
            int[] now = new int[sums.length];
            long all = 0;
            long apart = 0;
            long rough = 0;
            StringBuilder grid = new StringBuilder(now.length * 2);
            for (int cell = 0; cell < now.length; cell++) {
                now[cell] = (int) (sums[cell] / Math.max(1, counts[cell] * 3));
                all += now[cell];
                apart += last.length == now.length ? Math.abs(now[cell] - last[cell]) : 0;

                rough += cell > 0 ? Math.abs(now[cell] - now[cell - 1]) : 0;
                grid.append(HEX[now[cell] >> 4]).append(HEX[now[cell] & 15]);
            }
            LOGGER.info("[Hotwire] diag: hold {} frame {} {}: mean {}, apart from the reading before {}, rough {};"
                    + " gl error {} then {}; framebuffers draw {} read {}, viewport {},{} {}x{}, scissor {}; {};"
                    + " grid {}",
                    holds, frame, stage, all / now.length, last.length == now.length ? apart / now.length : -1,
                    rough / now.length, errorBefore, error, drawBefore, readBefore, viewport[0], viewport[1],
                    viewport[2], viewport[3], scissor, said, grid);
            last = now;
            if (frame == 0) {
                save(pixels, width, height, "hold-" + holds + "-" + stage + ".png");
            }
        } finally {
            MemoryUtil.memFree(pixels);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBefore);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readBefore);
        }
    }

    private static void save(ByteBuffer pixels, int width, int height, String name) {
        int[] colors = new int[width * height];
        for (int y = 0; y < height; y++) {
            int from = (height - 1 - y) * width * 4;
            for (int x = 0; x < width; x++) {
                int at = from + x * 4;
                colors[y * width + x] = 0xFF000000 | (pixels.get(at) & 0xFF) << 16 | (pixels.get(at + 1) & 0xFF) << 8
                        | (pixels.get(at + 2) & 0xFF);
            }
        }
        Path folder = FabricLoader.getInstance().getGameDir().resolve("hotwire").resolve("diag");
        Thread writer = new Thread(() -> {
            try {
                Files.createDirectories(folder);
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                image.setRGB(0, 0, width, height, colors, 0, width);
                ImageIO.write(image, "png", folder.resolve(name).toFile());
            } catch (IOException | RuntimeException | LinkageError e) {
                LOGGER.info("[Hotwire] diag: {} could not be saved: {}", name, e.toString());
            }
        }, "hotwire-diag-picture");
        writer.setDaemon(true);
        writer.start();
    }
}
