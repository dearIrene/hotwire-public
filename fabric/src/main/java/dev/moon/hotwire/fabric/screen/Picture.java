package dev.moon.hotwire.fabric.screen;

import dev.moon.hotwire.modrinth.WebpLossless;
import dev.moon.hotwire.ui.Canvas;
import dev.moon.hotwire.ui.IconPixels;
import dev.moon.hotwire.ui.OtherImages;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Picture implements Canvas.Image {
    private static final Logger LOGGER = LoggerFactory.getLogger(Picture.class);
    @SuppressWarnings("ThreadPriorityCheck")
    private static final ThreadPoolExecutor FITTER = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), Thread.ofPlatform().name("hotwire-pictures").daemon()
                    .priority(Thread.MIN_PRIORITY).factory());

    private final String name;
    private final IconPixels pixels;
    private @Nullable Identifier texture;
    private int textureWidth;
    private int textureHeight;
    private @Nullable CompletableFuture<int[]> fitting;
    private int fittingWidth;
    private int fittingHeight;

    private Picture(String name, IconPixels pixels) {
        this.name = name;
        this.pixels = pixels;
    }

    public static Optional<Picture> decode(String name, String url, byte[] bytes) {
        try {
            IconPixels pixels = WebpLossless.isWebp(bytes) ? WebpLossless.decode(bytes)
                    : OtherImages.decodePicture(bytes);
            return Optional.ofNullable(pixels).map(read -> new Picture(name, read));
        } catch (IOException | RuntimeException e) {
            LOGGER.info("[Hotwire] the picture {} could not be read: {}", url, e.toString());
            return Optional.empty();
        }
    }

    public int getWidth() {
        return this.pixels.getWidth();
    }

    public int getHeight() {
        return this.pixels.getHeight();
    }

    @Nullable Identifier findTexture(int width, int height, boolean mayMake) {
        if (this.texture != null && this.textureWidth == width && this.textureHeight == height) {
            return this.texture;
        }
        CompletableFuture<int[]> pending = this.fitting;
        if (pending == null || this.fittingWidth != width || this.fittingHeight != height) {
            this.fittingWidth = width;
            this.fittingHeight = height;
            this.fitting = CompletableFuture.supplyAsync(() -> this.pixels.makeFitted(width, height), FITTER);
            return this.texture;
        }
        if (!mayMake || !pending.isDone() || pending.isCompletedExceptionally()) {
            return this.texture;
        }
        this.release();
        this.texture = ModIcons.makeTexture("picture/" + this.name, width, height, pending.join());
        this.textureWidth = width;
        this.textureHeight = height;
        this.fitting = null;
        return this.texture;
    }

    public void release() {
        Identifier made = this.texture;
        if (made != null) {
            Minecraft.getInstance().getTextureManager().release(made);
            this.texture = null;
        }
    }
}
