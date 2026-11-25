package dev.moon.hotwire.engine;

import dev.moon.hotwire.config.HotwireSettings;
import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.modrinth.StoreCache;
import dev.moon.hotwire.modrinth.StoreModel;
import dev.moon.hotwire.seam.Games;
import dev.moon.hotwire.store.ModStore;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.ui.StoreCacheView;
import dev.moon.hotwire.ui.pages.StorePage;
import dev.moon.hotwire.ui.widgets.IconSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

final class StoreScreens {
    private final HotwireEngine engine;
    private final InstalledMods installed;
    private final StoreJars jars;
    private final IconSource icons;

    private final Set<String> fetching = new HashSet<>();

    private final Deque<Runnable> many = new ArrayDeque<>();
    private @Nullable ModStore store;
    private StoreModel.@Nullable Packs packs;

    StoreScreens(HotwireEngine engine, InstalledMods installed, StoreJars jars, IconSource icons) {
        this.engine = engine;
        this.installed = installed;
        this.icons = icons;
        this.jars = jars;
    }

    ModStore findStore() {
        ModStore made = this.store;
        if (made == null) {
            made = new ModStore(this.installed, this.jars.getCheck(), this.icons::of, this.icons);
            HotwireSettings settings = this.engine.getSettings();
            made.getModel().useHiddenCategories(settings.getStoreHidden(), settings::setStoreHidden);
            made.checkInstalled();
            StoreModel.Packs packs = this.engine.units.makePacks(made.getModel().getClient(),
                    made.getModel().getDownloads(),
                    this.engine::showMessage);
            made.getModel().setPacks(packs);
            this.packs = packs;
            this.store = made;
        }
        made.getModel().open();
        return made;
    }

    StoreCacheView getCacheView() {
        return new StoreCacheView() {
            private static final long SIZES_READ_NANOS = 1_000_000_000L;
            private Map<String, Long> sizes = Map.of();
            private long sizesAt;

            @Override
            public Map<String, Long> getSizes() {
                long now = System.nanoTime();
                if (this.sizesAt != 0 && now - this.sizesAt < SIZES_READ_NANOS) {
                    return this.sizes;
                }
                Map<String, Long> out = new HashMap<>();
                StoreScreens.this.findCache().findSizes().forEach((kind, bytes) -> out.put(
                        kind.name().toLowerCase(Locale.ROOT), bytes));
                this.sizes = out;
                this.sizesAt = now;
                return out;
            }

            @Override
            public void clear(@Nullable String kind) {
                StoreScreens.this.findCache().clear(kind == null ? null
                        : StoreCache.Kind.valueOf(kind.toUpperCase(Locale.ROOT)));
                this.sizesAt = 0;
            }

            @Override
            public int getLimit() {
                return CacheLimit.readStore();
            }

            @Override
            public void setLimit(int megabytes) {
                CacheLimit.writeStore(megabytes);
                StoreScreens.this.findCache().setLimitBytes((long) CacheLimit.readStore() << 20);
            }
        };
    }

    private StoreCache findCache() {
        ModStore made = this.store;
        return made != null ? made.getModel().getCache() : new StoreCache(CacheLimit.findStoreFolder()
                .resolve("answers"), (long) CacheLimit.readStore() << 20);
    }

    @Nullable ModStore getStore() {
        return this.store;
    }

    @Nullable StorePage findShown() {
        ModScreen screen = this.engine.screen;
        return screen != null && Games.getNow().isHotwireShown() ? screen.findShownStore() : null;
    }

    void openStoreNow(String words, @Nullable Object back) {
        ModStore made = this.findStore();
        if (!words.isEmpty()) {
            made.getModel().setWords(words);
        }
        this.openStore(made, back);
    }

    void addFromStoreNow(String projectId, String versionId, @Nullable Object back) {
        ModStore made = this.store;
        if (made != null) {
            this.many.clear();
            this.addFromStore(made, back, projectId, versionId, projectId, false);
        }
    }

    void addManyFromStoreNow(List<String> projectIds, @Nullable Object back) {
        ModStore made = this.store;
        if (made != null) {
            this.addManyFromStore(made, back, projectIds, projectIds);
        }
    }

    private void addManyFromStore(ModStore made, @Nullable Object back, List<String> projectIds,
            List<String> titles) {
        this.many.clear();
        for (int at = 0; at < projectIds.size(); at++) {
            String projectId = projectIds.get(at);
            String title = titles.get(at);
            this.many.add(() -> this.addFromStore(made, back, projectId, "", title, true));
        }
        this.addNextOfMany();
    }

    void addNextOfMany() {
        if (this.fetching.isEmpty() && !this.engine.getProgress().isBusy()) {
            Runnable next = this.many.poll();
            if (next != null) {
                next.run();
            }
        }
    }

    boolean hasMany() {
        return !this.many.isEmpty();
    }

    void dropMany() {
        this.many.clear();
    }

    void openStore(ModStore made, @Nullable Object back) {
        if (this.engine.screen == null || !Games.getNow().isHotwireShown()) {
            Games.getNow().showScreen(this.engine.getScreens().makeScreen(back));
        }
        Objects.requireNonNull(this.engine.screen).showStore(made.getModel(), new StorePage.Game() {
            @Override
            public void addFromStore(String projectId, String versionId, String title) {
                StoreScreens.this.many.clear();
                StoreScreens.this.addFromStore(made, back, projectId, versionId, title, false);
            }

            @Override
            public void addManyFromStore(List<ModrinthClient.Hit> hits) {
                StoreScreens.this.addManyFromStore(made, back, hits.stream().map(ModrinthClient.Hit::projectId)
                        .toList(), hits.stream().map(ModrinthClient.Hit::title).toList());
            }

            @Override
            public void queueFromStore(String projectId, String versionId, String title) {
                StoreScreens.this.queueFromStore(made, projectId, versionId, title);
            }

            @Override
            public void saveFromStore(String projectId, String versionId, String title) {
                StoreScreens.this.saveFromStore(made, projectId, versionId, title);
            }

            @Override
            public void openLink(String link) {
                Games.getNow().openLink(link);
            }
        }, made.getIcons(), made.getLoader(), made.getGameVersion());
    }

    private void queueFromStore(ModStore made, String projectId, String versionId, String title) {
        if (this.isShowingPacks(made)) {
            this.fetchPack(made, projectId, versionId, title);
            return;
        }
        if (!this.fetching.add(projectId)) {
            return;
        }
        made.fetch(projectId, versionId, title, (fetched, projects) -> {
            this.fetching.remove(projectId);
            this.jars.queueForNextStart(fetched, projects, this.engine.getPendingChanges());
        }, failure -> {
            this.fetching.remove(projectId);
            this.engine.showMessage(failure, true);
        });
    }

    private void saveFromStore(ModStore made, String projectId, String versionId, String title) {
        if (!this.fetching.add(projectId)) {
            return;
        }
        made.fetch(projectId, versionId, title, (fetched, projects) -> {
            this.fetching.remove(projectId);
            Path folder = StoreApi.findDownloads();
            try {
                Files.createDirectories(folder);
                for (Path jar : fetched) {
                    Files.copy(jar, folder.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
                this.engine.showMessage(Lang.get("hotwire.store.saved", title, folder), false);
                Games.getNow().openFolder(folder);
            } catch (IOException e) {
                this.engine.showMessage(Lang.get("hotwire.store.download_failed", title, e.getMessage()), true);
            }
        }, failure -> {
            this.fetching.remove(projectId);
            this.engine.showMessage(failure, true);
        });
    }

    private boolean isShowingPacks(ModStore made) {
        return this.packs != null && made.getModel().isShowingPacks();
    }

    private void fetchPack(ModStore made, String projectId, String versionId, String title) {
        StoreModel.Packs packs = this.packs;
        if (packs != null) {
            packs.fetch(made.getModel().getKind(), projectId, versionId, title);
        }
    }

    private void addFromStore(ModStore made, @Nullable Object back, String projectId, String versionId,
            String title, boolean unasked) {
        this.engine.showMessage(Lang.get("hotwire.store.downloading", title), false);
        if (this.isShowingPacks(made)) {
            this.fetchPack(made, projectId, versionId, title);
            return;
        }
        if (!this.fetching.add(projectId)) {
            return;
        }
        StoreModel model = made.getModel();
        StoreModel.Card card = model.getCardsByProject().get(projectId);
        boolean update = model.isInstalledProject(projectId) || (card != null && card.installed());
        made.fetch(projectId, versionId, title, (fetched, projects) -> {
            this.fetching.remove(projectId);
            if (fetched.size() > 1) {
                this.engine.showMessage(Lang.get("hotwire.store.getting_with", title, fetched.size() - 1), false);
            }
            if (!Games.getNow().isHotwireShown()) {
                Games.getNow().showScreen(this.engine.getScreens().makeScreen(back));
            }
            this.jars.queue(fetched, update, projects, unasked);
        }, failure -> {
            this.fetching.remove(projectId);
            this.engine.showMessage(failure, true);

            this.addNextOfMany();
        });
    }
}
