package dev.moon.hotwire.fabric.store;

import dev.moon.hotwire.fabric.screen.ModIcons;
import dev.moon.hotwire.modrinth.StoreCache;
import dev.moon.hotwire.modrinth.StoreModel;
import dev.moon.hotwire.ui.Canvas;
import dev.moon.hotwire.ui.StoreIcons;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StoreIconLoader implements StoreIcons {
    private static final Logger LOGGER = LoggerFactory.getLogger(StoreIconLoader.class);
    private static final int MOST_LIVE = 160;

    private final StoreModel model;
    private final StoreCache cache;
    private final Map<String, CompletableFuture<Optional<ModIcons.Icon>>> icons = new ConcurrentHashMap<>();

    private final LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>(MOST_LIVE, 0.75f, true);

    public StoreIconLoader(StoreModel model, StoreCache cache) {
        this.model = model;
        this.cache = cache;
    }

    @Override
    public Canvas.@Nullable Image find(String url) {
        if (!url.startsWith("https://cdn.modrinth.com/")) {
            return null;
        }
        this.seen.put(url, Boolean.TRUE);
        if (this.seen.size() > MOST_LIVE) {
            String oldest = this.seen.keySet().iterator().next();
            this.seen.remove(oldest);
            CompletableFuture<Optional<ModIcons.Icon>> gone = this.icons.remove(oldest);
            if (gone != null) {
                gone.getNow(Optional.empty()).ifPresent(ModIcons.Icon::release);
            }
        }
        return this.icons.computeIfAbsent(url, this::load).getNow(Optional.empty()).orElse(null);
    }

    private CompletableFuture<Optional<ModIcons.Icon>> load(String url) {
        CompletableFuture<Optional<ModIcons.Icon>> future = new CompletableFuture<>();
        this.model.run(() -> {
            try {
                Optional<byte[]> bytes = this.cache.findIcon(url);
                if (bytes.isEmpty()) {
                    bytes = Optional.of(this.model.getClient().fetchFromCdn(url));
                    bytes.ifPresent(got -> this.cache.saveIcon(url, got));
                }
                String name = "store-" + Integer.toHexString(url.hashCode());
                future.complete(bytes.flatMap(got -> ModIcons.decodeFor(name, url, got)));
            } catch (IOException | RuntimeException e) {
                LOGGER.info("[Hotwire] the store could not get an icon: {}", e.toString());
                future.complete(Optional.empty());
            }
        });
        return future;
    }

    @Override
    public void releaseAll() {
        Minecraft.getInstance().execute(() -> {
            this.icons.values().forEach(future -> future.getNow(Optional.empty()).ifPresent(ModIcons.Icon::release));
            this.icons.clear();
            this.seen.clear();
        });
    }
}
