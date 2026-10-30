package dev.moon.hotwire.api;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

public interface HotwireApi {
    static HotwireApi getInstance() {
        return ApiHolder.INSTANCE;
    }

    int getRevision();

    boolean isReady();

    boolean isSwitching();

    SwitchProgress getProgress();

    List<ModState> getMods();

    Optional<ModState> findMod(String id);

    SwitchPreview previewOff(Collection<String> ids);

    SwitchPreview previewOn(Collection<String> ids);

    CompletableFuture<SwitchResult> turnOff(Collection<String> ids);

    CompletableFuture<SwitchResult> turnOn(Collection<String> ids);

    void addListener(Consumer<SwitchResult> listener);

    boolean showPrompt();

    @Nullable OpeningPrompt findOpeningPrompt();

    void openScreen();

    CompletableFuture<StorePage> searchStore(StoreQuery query);

    List<StoreCategory> getStoreCategories(StoreKind kind);

    CompletableFuture<List<String>> findStoreGameVersions();

    CompletableFuture<Optional<StoreProject>> findStoreProject(StoreQuery browsing, String idOrSlug);

    CompletableFuture<StoreAdd> requestAdd(String idOrSlug, String versionId);

    CompletableFuture<SwitchResult> queueForNextStart(String idOrSlug, String versionId);

    CompletableFuture<SwitchResult> saveToDownloads(StoreQuery browsing, String idOrSlug, String versionId);

    void fetchPack(StoreKind kind, String idOrSlug, String versionId);

    StorePackState findPackState(StoreKind kind, String projectId);

    void togglePack(StoreKind kind, String projectId);

    List<StoreDownload> getStoreDownloads();

    CompletableFuture<Path> writeReport(String why);
}
