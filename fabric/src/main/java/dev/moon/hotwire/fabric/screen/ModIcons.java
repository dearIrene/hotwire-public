package dev.moon.hotwire.fabric.screen;

import com.mojang.blaze3d.platform.NativeImage;
import dev.moon.hotwire.fabric.InstalledMods;
import dev.moon.hotwire.jar.JarLookup;
import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.jar.ModJarReader;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.modrinth.WebpLossless;
import dev.moon.hotwire.ui.Canvas;
import dev.moon.hotwire.ui.IconPixels;
import dev.moon.hotwire.ui.OtherImages;
import dev.moon.hotwire.ui.SvgIcon;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModIcons implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModIcons.class);

    private record Source(String id, @Nullable Path jar, String path) {
    }

    public static final class Icon implements Canvas.Image {
        private final String name;
        private final IconPixels pixels;
        private final Map<Integer, Identifier> textures = new HashMap<>();
        private double scale;

        private Icon(String name, IconPixels pixels) {
            this.name = name;
            this.pixels = pixels;
        }

        @Nullable Identifier findTexture(int size, double scale, boolean mayMake) {
            if (scale != this.scale) {
                this.release();
                this.scale = scale;
            }
            Identifier made = this.textures.get(size);
            if (made != null || !mayMake || size <= 0) {
                return made;
            }
            Identifier id = makeTexture("icon/" + this.name + "/" + size, size, size, this.pixels.makeSquare(size));
            this.textures.put(size, id);
            return id;
        }

        public void release() {
            this.textures.values().forEach(Minecraft.getInstance().getTextureManager()::release);
            this.textures.clear();
        }
    }

    static Identifier makeTexture(String path, int width, int height, int[] argb) {
        NativeImage image = new NativeImage(width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                //? if >=1.21.4 {
                image.setPixel(x, y, argb[y * width + x]);
                //?} else {
                /*image.setPixelRGBA(x, y, swapRedBlue(argb[y * width + x]));
                *///?}
            }
        }
        Identifier id = Identifier.fromNamespaceAndPath("hotwire", path);
        //? if >=1.21.5 {
        DynamicTexture texture = new DynamicTexture(() -> "hotwire/" + path, image);
        //?} else {
        /*DynamicTexture texture = new DynamicTexture(image);
        *///?}
        Minecraft.getInstance().getTextureManager().register(id, texture);
        return id;
    }

    private final InstalledMods installed;
    private final Map<String, Source> sources = new ConcurrentHashMap<>();
    private final Map<Source, CompletableFuture<Optional<Icon>>> icons = new ConcurrentHashMap<>();

    @SuppressWarnings("ThreadPriorityCheck")
    private final ThreadPoolExecutor reader = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), Thread.ofPlatform().name("hotwire-icons").daemon()
                    .priority(Thread.MIN_PRIORITY).factory());

    public ModIcons(InstalledMods installed) {
        this.installed = installed;
    }

    public Canvas.@Nullable Image of(String id) {
        Source source = this.getSourceOf(id);
        Source was = this.sources.put(id, source);
        if (was != null && !was.equals(source)) {
            CompletableFuture<Optional<Icon>> old = this.icons.remove(was);
            if (old != null) {
                old.getNow(Optional.empty()).ifPresent(Icon::release);
            }
        }
        return this.read(source).getNow(Optional.empty()).orElse(null);
    }

    public Canvas.@Nullable Image ofJar(Path jar) {
        ModManifest manifest;
        try {
            manifest = ModJarReader.read(jar).manifest();
        } catch (ModJarException e) {
            return null;
        }
        return this.read(new Source(manifest.id(), jar, getIconPath(manifest.id(), manifest.icon()))).join()
                .orElse(null);
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    public void readAhead(Collection<String> ids) {
        ids.forEach(id -> this.read(this.sources.computeIfAbsent(id, this::getSourceOf)));
    }

    private Source getSourceOf(String id) {
        Path jar = this.installed.findJarOf(id);
        boolean fromJar = jar != null && this.installed.isTopLevel(id)
                && (this.installed.isAddedThisSession(id) || this.installed.isUpdatedThisSession(id));
        return new Source(id, fromJar ? jar : null,
                getIconPath(id, this.installed.find(id).flatMap(ModManifest::icon)));
    }

    private static String getIconPath(String id, Optional<String> declared) {
        String path = declared.orElse("assets/" + id + "/icon.png");
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private CompletableFuture<Optional<Icon>> read(Source source) {
        return this.icons.computeIfAbsent(source, key -> CompletableFuture.supplyAsync(() -> decode(key),
                this.reader).exceptionally(failure -> {
                    LOGGER.info("[Hotwire] {}'s icon {} could not be read: {}", key.id(), key.path(),
                            failure.toString());
                    return Optional.empty();
                }));
    }

    public static Optional<Icon> decodeFor(String name, String path, byte[] bytes) {
        try {
            IconPixels pixels;
            if (path.toLowerCase(Locale.ROOT).endsWith(".svg")) {
                pixels = SvgIcon.render(bytes);
            } else if (WebpLossless.isWebp(bytes)) {
                pixels = WebpLossless.decode(bytes);
            } else if (OtherImages.isKnown(bytes)) {
                pixels = OtherImages.decode(bytes);
            } else {
                pixels = decodePng(bytes);
            }
            return Optional.ofNullable(pixels).map(retained -> new Icon(name, retained));
        } catch (IOException | RuntimeException e) {
            LOGGER.info("[Hotwire] the icon {} could not be read: {}", path, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<Icon> decode(Source source) {
        try {
            Path jar = source.jar();
            Optional<byte[]> bytes = jar == null ? fromGame(source) : fromJar(jar, source.path());
            if (bytes.isEmpty()) {
                return Optional.empty();
            }
            IconPixels pixels;
            if (source.path().toLowerCase(Locale.ROOT).endsWith(".svg")) {
                pixels = SvgIcon.render(bytes.get());
            } else {
                pixels = decodePng(bytes.get());
            }
            String name = (jar == null ? "" : "jar-" + Integer.toHexString(jar.hashCode()) + "-")
                    + source.id().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
            return Optional.ofNullable(pixels).map(retained -> new Icon(name, retained));
        } catch (IOException | RuntimeException e) {
            LOGGER.info("[Hotwire] {}'s icon {} could not be read: {}", source.id(), source.path(), e.toString());
            return Optional.empty();
        }
    }

    private static @Nullable IconPixels decodePng(byte[] bytes) throws IOException {
        try (NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes))) {
            //? if >=1.21.4 {
            int[] argb = image.getPixels();
            //?} else {
            /*int[] argb = image.getPixelsRGBA();
            for (int i = 0; i < argb.length; i++) {
                argb[i] = swapRedBlue(argb[i]);
            }
            *///?}
            return IconPixels.of(image.getWidth(), image.getHeight(), argb);
        }
    }

    private static Optional<byte[]> fromGame(Source source) throws IOException {
        Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(source.id());
        if (container.isEmpty()) {
            return Optional.empty();
        }
        String declared = container.get().getMetadata().getIconPath(Integer.MAX_VALUE).orElse(source.path());
        Optional<Path> file = container.get().findPath(declared).filter(Files::isRegularFile);
        return file.isEmpty() ? Optional.empty() : Optional.of(Files.readAllBytes(file.get()));
    }

    private static Optional<byte[]> fromJar(Path jar, String path) throws IOException {
        return Optional.ofNullable(JarLookup.readEntry(jar, path));
    }

    //? if <1.21.4 {
    /*// The game before 1.21.4 keeps a pixel as alpha, blue, green, red.
    private static int swapRedBlue(int pixel) {
        return (pixel & 0xFF00FF00) | ((pixel & 0xFF) << 16) | ((pixel >>> 16) & 0xFF);
    }
    *///?}

    @Override
    public void close() {
        this.reader.shutdownNow();
        this.icons.values().forEach(icon -> icon.getNow(Optional.empty()).ifPresent(Icon::release));
        this.icons.clear();
    }
}
