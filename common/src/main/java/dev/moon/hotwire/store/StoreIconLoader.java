package dev.moon.hotwire.store;

import dev.moon.hotwire.modrinth.StoreCache;
import dev.moon.hotwire.modrinth.StoreModel;
import dev.moon.hotwire.ui.Canvas;
import dev.moon.hotwire.ui.ImageDecoder;
import dev.moon.hotwire.ui.StoreIcons;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StoreIconLoader implements StoreIcons {
    private static final Logger LOGGER = LoggerFactory.getLogger(StoreIconLoader.class);
    private static final int MOST_LIVE = 160;

    private static final int FETCHERS = 6;

    private static final int MOST_PICTURES = 16;

    private final StoreModel model;
    private final StoreCache cache;

    private final Function<String, Canvas.@Nullable Image> local;
    @SuppressWarnings("ThreadPriorityCheck")
    private final ExecutorService fetchers = Executors.newFixedThreadPool(FETCHERS, Thread.ofPlatform()
            .name("hotwire-store-icons-", 0).daemon().priority(Thread.MIN_PRIORITY).factory());
    private final ImageDecoder decoder;
    private final Map<String, CompletableFuture<Optional<Canvas.Image>>> icons = new ConcurrentHashMap<>();

    private final LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>(MOST_LIVE, 0.75f, true);
    private final Map<String, CompletableFuture<Optional<PagePicture>>> pictures = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, Boolean> picturesSeen = new LinkedHashMap<>(MOST_PICTURES, 0.75f, true);

    public StoreIconLoader(StoreModel model, StoreCache cache, Function<String, Canvas.@Nullable Image> local,
            ImageDecoder decoder) {
        this.decoder = decoder;
        this.model = model;
        this.cache = cache;
        this.local = local;
    }

    @Override
    public Canvas.@Nullable Image find(String projectId, String url) {
        Canvas.Image own = this.local.apply(projectId);
        if (own != null) {
            return own;
        }
        if (!url.startsWith("https://cdn.modrinth.com/")) {
            return null;
        }
        this.seen.put(url, Boolean.TRUE);
        if (this.seen.size() > MOST_LIVE) {
            String oldest = this.seen.keySet().iterator().next();
            this.seen.remove(oldest);
            CompletableFuture<Optional<Canvas.Image>> gone = this.icons.remove(oldest);
            if (gone != null) {
                gone.getNow(Optional.empty()).ifPresent(Canvas.Image::release);
            }
        }
        return this.icons.computeIfAbsent(url, this::load).getNow(Optional.empty()).orElse(null);
    }

    @Override
    public @Nullable PagePicture findPicture(String url) {
        this.picturesSeen.put(url, Boolean.TRUE);
        if (this.picturesSeen.size() > MOST_PICTURES) {
            String oldest = this.picturesSeen.keySet().iterator().next();
            this.picturesSeen.remove(oldest);
            CompletableFuture<Optional<PagePicture>> gone = this.pictures.remove(oldest);
            if (gone != null) {
                gone.getNow(Optional.empty()).ifPresent(picture -> picture.image().release());
            }
        }
        return this.pictures.computeIfAbsent(url, this::loadPicture).getNow(Optional.empty()).orElse(null);
    }

    private CompletableFuture<Optional<PagePicture>> loadPicture(String url) {
        CompletableFuture<Optional<PagePicture>> future = new CompletableFuture<>();
        this.fetchers.execute(() -> {
            try {
                byte[] bytes = this.model.getClient().fetchPicture(url);
                future.complete(this.decoder.decodePicture("store-" + Integer.toHexString(url.hashCode()), url, bytes));
            } catch (IOException | RuntimeException e) {
                LOGGER.info("[Hotwire] the store could not get a picture: {}", e.toString());
                future.complete(Optional.empty());
            }
        });
        return future;
    }

    private CompletableFuture<Optional<Canvas.Image>> load(String url) {
        CompletableFuture<Optional<Canvas.Image>> future = new CompletableFuture<>();
        this.fetchers.execute(() -> {
            try {
                Optional<byte[]> bytes = this.cache.find(StoreCache.Kind.PICTURE, url).map(StoreCache.Entry::bytes);
                if (bytes.isEmpty()) {
                    bytes = Optional.of(this.model.getClient().fetchFromCdn(url));
                    bytes.ifPresent(got -> this.cache.save(StoreCache.Kind.PICTURE, url, got));
                }
                String name = "store-" + Integer.toHexString(url.hashCode());
                future.complete(bytes.flatMap(got -> this.decoder.decodeIcon(name, url, got)));
            } catch (IOException | RuntimeException e) {
                LOGGER.info("[Hotwire] the store could not get an icon: {}", e.toString());
                future.complete(Optional.empty());
            }
        });
        return future;
    }
}
