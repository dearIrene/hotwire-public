package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.screen.ModIcons;
import dev.moon.hotwire.fabric.screen.StoreGameScreen;
import dev.moon.hotwire.fabric.screen.WebLinks;
import dev.moon.hotwire.fabric.store.ModStore;
import dev.moon.hotwire.fabric.store.PackFolders;
import dev.moon.hotwire.modrinth.StoreCache;
import dev.moon.hotwire.modrinth.StoreModel;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.StoreCacheView;
import dev.moon.hotwire.ui.StoreScreen;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

final class StoreScreens {
    private final HotwireEngine engine;
    private final InstalledMods installed;
    private final StoreJars jars;
    private final ModIcons icons;

    private final Set<String> fetching = new HashSet<>();
    private @Nullable ModStore store;
    private @Nullable PackFolders packs;
    private @Nullable StoreScreen shown;

    StoreScreens(HotwireEngine engine, InstalledMods installed, StoreJars jars, ModIcons icons) {
        this.engine = engine;
        this.installed = installed;
        this.icons = icons;
        this.jars = jars;
    }

    ModStore findStore() {
        ModStore made = this.store;
        if (made == null) {
            made = new ModStore(this.installed, this.icons::of);
            made.checkInstalled();
            PackFolders packs = new PackFolders(made.getModel().getClient(), this.engine::showMessage);
            made.getModel().setPacks(packs);
            this.packs = packs;
            this.store = made;
        }
        made.getModel().open();
        return made;
    }

    StoreCacheView getCacheView() {
        return new StoreCacheView() {
            @Override
            public Map<String, Long> getSizes() {
                Map<String, Long> out = new HashMap<>();
                StoreScreens.this.findCache().findSizes().forEach((kind, bytes) -> out.put(
                        kind.name().toLowerCase(Locale.ROOT), bytes));
                return out;
            }

            @Override
            public void clear(@Nullable String kind) {
                StoreScreens.this.findCache().clear(kind == null ? null
                        : StoreCache.Kind.valueOf(kind.toUpperCase(Locale.ROOT)));
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

    @Nullable StoreScreen findShown() {
        StoreScreen up = this.shown;
        return up != null && ModViews.openScreen() instanceof StoreGameScreen ? up : null;
    }

    void openStoreNow(String words, @Nullable Screen back) {
        ModStore made = this.findStore();
        if (!words.isEmpty()) {
            made.getModel().setWords(words);
        }
        this.openStore(made, back);
    }

    void addFromStoreNow(String projectId, String versionId, @Nullable Screen back) {
        ModStore made = this.store;
        if (made != null) {
            this.addFromStore(made, back, projectId, versionId, projectId);
        }
    }

    void openStore(ModStore made, @Nullable Screen back) {
        StoreScreen screen = new StoreScreen(made.getModel(), new StoreScreen.Game() {
            @Override
            public void addFromStore(String projectId, String versionId, String title) {
                StoreScreens.this.addFromStore(made, back, projectId, versionId, title);
            }

            @Override
            public void queueFromStore(String projectId, String versionId, String title) {
                StoreScreens.this.queueFromStore(made, projectId, versionId, title);
            }

            @Override
            public void openLink(String link) {
                WebLinks.open(link);
            }

            @Override
            public void onBack() {
                StoreScreens.this.engine.forgetUnder();
                ModViews.show(back);
            }
        }, this.engine, made.getIcons(), made.getLoader(), made.getGameVersion());
        this.shown = screen;
        ModViews.show(new StoreGameScreen(screen, () -> {
            if (this.shown == screen) {
                this.shown = null;
            }
        }));
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

    private boolean isShowingPacks(ModStore made) {
        return this.packs != null && made.getModel().isShowingPacks();
    }

    private void fetchPack(ModStore made, String projectId, String versionId, String title) {
        PackFolders packs = this.packs;
        if (packs != null) {
            packs.fetch(made.getModel().getKind(), projectId, versionId, title);
        }
    }

    private void addFromStore(ModStore made, @Nullable Screen back, String projectId, String versionId,
            String title) {
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
            if (this.findShown() == null) {
                ModViews.show(this.engine.makeScreen(back));
            }
            this.jars.queue(fetched, update, projects);
        }, failure -> {
            this.fetching.remove(projectId);
            this.engine.showMessage(failure, true);
        });
    }
}
