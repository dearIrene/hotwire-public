package dev.moon.hotwire.fabric.screen;

import com.mojang.blaze3d.platform.NativeImage;
import dev.moon.hotwire.add.LoaderModList;
import dev.moon.hotwire.engine.CacheLimit;
import dev.moon.hotwire.engine.InstalledMods;
import dev.moon.hotwire.jar.JarLookup;
import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.jar.ModJarReader;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.modrinth.StoreCache;
import dev.moon.hotwire.modrinth.WebpLossless;
import dev.moon.hotwire.ui.widgets.Canvas;
import dev.moon.hotwire.ui.widgets.IconPixels;
import dev.moon.hotwire.ui.widgets.IconSource;
import dev.moon.hotwire.ui.widgets.OtherImages;
import dev.moon.hotwire.ui.widgets.StoreIcons;
import dev.moon.hotwire.ui.widgets.SvgIcon;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModIcons implements AutoCloseable, IconSource {
    @Override
    public Optional<Canvas.Image> decodeIcon(String name, String url, byte[] bytes) {
        return decodeFor(name, url, bytes).map(icon -> icon);
    }

    @Override
    public Optional<StoreIcons.PagePicture> decodePicture(String name, String url, byte[] bytes) {
        return Picture.decode(name, url, bytes)
                .map(picture -> new StoreIcons.PagePicture(picture, picture.getWidth(), picture.getHeight()));
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ModIcons.class);

    private record Source(String id, @Nullable Path jar, String path) {
    }

    public static final class Icon implements Canvas.Image {
        private final String name;
        private final String path;
        private final byte @Nullable [] encoded;
        private final @Nullable IconPixels pixels;

        private final @Nullable Source again;

        private final Map<Integer, int[]> ahead = new ConcurrentHashMap<>();
        private final Set<Integer> decoding = ConcurrentHashMap.newKeySet();
        private final Map<Integer, Identifier> textures = new HashMap<>();
        private volatile double scale;

        private Icon(String name, String path, byte @Nullable [] encoded, @Nullable IconPixels pixels, double scale) {
            this(name, path, encoded, pixels, scale, null);
        }

        private Icon(String name, String path, byte @Nullable [] encoded, @Nullable IconPixels pixels, double scale,
                @Nullable Source again) {
            this.again = again;
            this.name = name;
            this.path = path;
            this.encoded = encoded;
            this.pixels = pixels;
            this.scale = scale;
        }

        @Nullable Identifier findTexture(int size, double scale, boolean mayMake) {
            if (scale != this.scale) {
                this.release();
                this.ahead.clear();
                this.scale = scale;
            }
            Identifier made = this.textures.get(size);
            if (made != null || !mayMake || size <= 0) {
                return made;
            }
            int[] square = this.ahead.remove(size);
            IconPixels decoded = this.pixels;
            if (square == null && decoded != null) {
                square = decoded.makeSquare(size);
            }
            if (square == null) {
                this.decodeSoon(size, scale);
                return null;
            }
            Identifier id = makeTexture("icon/" + this.name + "/" + size, size, size, square);
            this.textures.put(size, id);
            return id;
        }

        @SuppressWarnings("FutureReturnValueIgnored")
        private void decodeSoon(int size, double scale) {
            byte[] inMemory = this.encoded;
            Source from = this.again;
            if ((inMemory == null && from == null) || !this.decoding.add(size)) {
                return;
            }

            DECODER.execute(() -> {
                boolean failed = true;
                try {
                    byte[] file = inMemory != null ? inMemory : readSource(Objects.requireNonNull(from)).orElse(null);
                    IconPixels decoded = file == null ? null : decode(this.path, file);
                    failed = decoded == null;
                    if (decoded != null && scale == this.scale) {
                        this.ahead.put(size, decoded.makeSquare(size));
                    }
                } finally {
                    if (!failed) {
                        this.decoding.remove(size);
                    }
                }
            });
        }

        @Override
        public void release() {
            Minecraft game = Minecraft.getInstance();
            if (!game.isSameThread()) {
                game.execute(this::release);
                return;
            }
            this.textures.values().forEach(game.getTextureManager()::release);
            this.textures.clear();
        }
    }

    private static volatile @Nullable StoreCache squares;

    private static StoreCache findSquares() {
        StoreCache made = squares;
        if (made == null) {
            made = new StoreCache(CacheLimit.findStoreFolder().resolve("answers"), (long) CacheLimit.readStore() << 20);
            squares = made;
        }
        return made;
    }

    private static @Nullable String findSquareKey(Source source, int size) {
        try {
            Path jar = source.jar();
            String stamp = jar != null ? Files.size(jar) + "@" + Files.getLastModifiedTime(jar).toMillis()
                    : findContainer(source.id()).map(found -> found.getMetadata().getVersion().getFriendlyString())
                            .orElse(null);
            return stamp == null ? null : "modicon:" + source.id() + "|" + (jar == null ? "game" : jar) + "|"
                    + stamp + "|" + source.path() + "|" + size;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static byte[] packSquare(int size, int[] argb) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(argb.length);
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
            out.writeInt(size);
            for (int pixel : argb) {
                out.writeInt(pixel);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    static int @Nullable [] unpackSquare(byte[] packed, int size) {
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(packed)))) {
            if (in.readInt() != size) {
                return null;
            }
            int[] argb = new int[size * size];
            for (int i = 0; i < argb.length; i++) {
                argb[i] = in.readInt();
            }
            return argb;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static final ThreadPoolExecutor DECODER = makeWorker("hotwire-icon-decode");

    @SuppressWarnings("ThreadPriorityCheck")
    static ThreadPoolExecutor makeWorker(String name) {
        return new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                Thread.ofPlatform().name(name).daemon().priority(Thread.MIN_PRIORITY).factory());
    }

    private static Optional<Icon> makeIcon(String name, String path, byte[] encoded, int box) {
        IconPixels decoded = decode(path, encoded);
        if (decoded == null) {
            return Optional.empty();
        }

        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        int size = Math.max(0, (int) Math.round(box * scale));
        long asFile = encoded.length + 4L * size * size;
        boolean byFile = asFile < 4L * decoded.getWidth() * decoded.getHeight();
        Icon icon = new Icon(name, path, byFile ? encoded : null, byFile ? null : decoded, scale);
        if (byFile && size > 0) {
            icon.ahead.put(size, decoded.makeSquare(size));
        }
        return Optional.of(icon);
    }

    private static @Nullable IconPixels decode(String path, byte[] encoded) {
        try {
            return decodePixels(path, encoded);
        } catch (IOException | RuntimeException e) {
            LOGGER.info("[Hotwire] the icon {} could not be read: {}", path, e.toString());
            return null;
        }
    }

    static @Nullable IconPixels decodePixels(String path, byte[] bytes) throws IOException {
        if (path.toLowerCase(Locale.ROOT).endsWith(".svg")) {
            return SvgIcon.render(bytes);
        }
        if (WebpLossless.isWebp(bytes)) {
            return WebpLossless.decode(bytes);
        }
        if (OtherImages.isKnown(bytes)) {
            return OtherImages.decode(bytes);
        }
        return decodePng(bytes);
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

    private final ThreadPoolExecutor reader = makeWorker("hotwire-icons");

    public ModIcons(InstalledMods installed) {
        this.installed = installed;
    }

    @Override
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

    @Override
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
    @Override
    public void readAhead(Collection<String> ids) {
        ids.forEach(id -> this.read(this.sources.computeIfAbsent(id, this::getSourceOf)));
    }

    private Source getSourceOf(String id) {
        Path running = this.installed.findJarOf(id);
        Path jar = running != null ? running : this.installed.findFolderJarOf(id);

        boolean fromJar = jar != null && (FabricLoader.getInstance().getModContainer(id).isEmpty()
                || (this.installed.isTopLevel(id)
                && (this.installed.isAddedThisSession(id) || this.installed.isUpdatedThisSession(id))));
        return new Source(id, fromJar ? jar : null,
                getIconPath(id, this.installed.find(id).or(() -> this.installed.findDisabled(id))
                        .flatMap(ModManifest::icon)));
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

    public static Optional<byte[]> readFile(Path jar, String id, Optional<String> declared) throws IOException {
        Optional<byte[]> file = fromJar(jar, getIconPath(id, declared));
        if (file.isPresent()) {
            return file;
        }
        Optional<String> only = findOnlyImageInJar(jar, id);
        return only.isEmpty() ? Optional.empty() : fromJar(jar, only.get());
    }

    public static Optional<Icon> decodeFor(String name, String path, byte[] bytes) {
        return makeIcon(name, path, bytes, IconPixels.STORE_BOX);
    }

    private static Optional<Icon> decode(Source source) {
        Path jar = source.jar();
        String name = (jar == null ? "" : "jar-" + Integer.toHexString(jar.hashCode()) + "-")
                + source.id().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        int size = (int) Math.round(IconPixels.LIST_BOX * scale);
        String key = size > 0 ? findSquareKey(source, size) : null;
        StoreCache.Entry entry = key == null ? null : findSquares().find(StoreCache.Kind.PICTURE, key).orElse(null);
        int[] saved = entry == null ? null : unpackSquare(entry.bytes(), size);
        if (saved != null) {
            Icon icon = new Icon(name, source.path(), null, null, scale, source);
            icon.ahead.put(size, saved);
            return Optional.of(icon);
        }
        Optional<Icon> made = decodeFile(source, name);
        if (key != null) {
            made.ifPresent(icon -> {
                int[] shown = icon.ahead.get(size);
                IconPixels pixels = icon.pixels;
                if (shown == null && pixels != null) {
                    shown = pixels.makeSquare(size);
                }
                if (shown != null) {
                    findSquares().save(StoreCache.Kind.PICTURE, key, packSquare(size, shown));
                }
            });
        }
        return made;
    }

    private static Optional<byte[]> readSource(Source source) {
        try {
            Path jar = source.jar();
            Optional<byte[]> bytes = jar == null ? fromGame(source) : fromJar(jar, source.path());
            if (bytes.isPresent()) {
                return bytes;
            }
            Optional<String> only = jar == null ? findOnlyImageInGame(source.id()) : findOnlyImageInJar(jar,
                    source.id());
            if (only.isEmpty()) {
                return Optional.empty();
            }
            return jar == null ? fromGameFile(source.id(), only.get()) : fromJar(jar, only.get());
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<Icon> decodeFile(Source source, String name) {
        try {
            Path jar = source.jar();
            Optional<byte[]> bytes = jar == null ? fromGame(source) : fromJar(jar, source.path());
            String path = source.path();
            if (bytes.isEmpty()) {
                Optional<String> only = jar == null ? findOnlyImageInGame(source.id()) : findOnlyImageInJar(jar,
                        source.id());
                if (only.isEmpty()) {
                    return Optional.empty();
                }
                path = only.get();
                bytes = jar == null ? fromGameFile(source.id(), path) : fromJar(jar, path);
                if (bytes.isEmpty()) {
                    return Optional.empty();
                }
            }
            return makeIcon(name, path, bytes.get(), IconPixels.LIST_BOX);
        } catch (IOException | RuntimeException e) {
            LOGGER.info("[Hotwire] {}'s icon {} could not be read: {}", source.id(), source.path(), e.toString());
            return Optional.empty();
        }
    }

    private static @Nullable IconPixels decodePng(byte[] bytes) throws IOException {
        OtherImages.checkPngSize(bytes);
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
        Optional<ModContainer> container = findContainer(source.id());
        if (container.isEmpty()) {
            return Optional.empty();
        }
        String declared = container.get().getMetadata().getIconPath(Integer.MAX_VALUE).orElse(source.path());
        Optional<Path> file = container.get().findPath(declared).filter(Files::isRegularFile);
        return file.isEmpty() ? Optional.empty() : Optional.of(Files.readAllBytes(file.get()));
    }

    private static Optional<byte[]> fromGameFile(String id, String path) throws IOException {
        Optional<Path> file = findContainer(id).flatMap(mod -> mod.findPath(path)).filter(Files::isRegularFile);
        return file.isEmpty() ? Optional.empty() : Optional.of(Files.readAllBytes(file.get()));
    }

    private static boolean isImage(String name) {
        String low = name.toLowerCase(Locale.ROOT);
        return low.endsWith(".png") || low.endsWith(".svg") || low.endsWith(".webp");
    }

    private static Optional<String> pickOnly(List<String> images, String id) {
        if (images.size() == 1) {
            return Optional.of(images.getFirst());
        }
        String own = id.toLowerCase(Locale.ROOT) + ".";
        Optional<String> namedAfter = images.stream().filter(path -> path.substring(path.lastIndexOf('/') + 1)
                .toLowerCase(Locale.ROOT).startsWith(own)).findFirst();
        if (namedAfter.isPresent()) {
            return namedAfter;
        }
        List<String> named = images.stream().filter(path -> {
            String file = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            return file.contains("icon") || file.contains("logo");
        }).toList();
        return named.size() == 1 ? Optional.of(named.getFirst()) : Optional.empty();
    }

    private static Optional<ModContainer> findContainer(String id) {
        Optional<ModContainer> listed = FabricLoader.getInstance().getModContainer(id);
        return listed.isPresent() ? listed : Optional.ofNullable(LoaderModList.getHiddenContainers().get(id));
    }

    private static Optional<String> findOnlyImageInGame(String id) throws IOException {
        Optional<ModContainer> container = findContainer(id);
        String folder = "assets/" + id;
        Optional<Path> found = container.flatMap(one -> one.findPath(folder)).filter(Files::isDirectory);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(found.get())) {
            return pickOnly(files.map(file -> file.getFileName().toString()).filter(ModIcons::isImage)
                    .map(name -> folder + "/" + name).toList(), id);
        }
    }

    private static Optional<String> findOnlyImageInJar(Path jar, String id) throws IOException {
        String folder = "assets/" + id + "/";
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return pickOnly(zip.stream().map(ZipEntry::getName).filter(name -> name.startsWith(folder)
                    && name.indexOf('/', folder.length()) < 0 && isImage(name)).toList(), id);
        }
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
