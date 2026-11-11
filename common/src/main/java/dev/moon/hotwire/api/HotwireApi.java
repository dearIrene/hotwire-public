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

    default boolean hasRevision(int revision) {
        return this.getRevision() >= revision;
    }

    ApiStatus getStatus();

    SwitchProgress getProgress();

    List<ModState> getMods();

    Optional<ModState> findMod(String modId);

    SwitchPreview previewOff(Collection<String> modIds);

    SwitchPreview previewOn(Collection<String> modIds);

    CompletableFuture<SwitchResult> turnOff(Collection<String> modIds);

    CompletableFuture<SwitchResult> turnOn(Collection<String> modIds);

    CompletableFuture<ModUpdates> checkUpdates();

    CompletableFuture<ChangePlan> planChange(ChangeRequest request);

    CompletableFuture<Void> awaitIdle();

    List<PendingTask> getPendingTasks();

    boolean cancelPendingTask(String modId);

    boolean isAddedThisSession(String modId);

    Optional<byte[]> findIcon(String modId);

    String findNewerHotwire();

    CompletableFuture<SwitchResult> updateHotwire();

    Registration addListener(Consumer<SwitchResult> listener);

    Registration addLifecycleListener(ModLifecycleListener listener);

    Registration addLifecycleListener(String modId, ModLifecycleListener listener);

    boolean showPrompt();

    @Nullable OpeningPrompt findOpeningPrompt();

    void openScreen();

    CompletableFuture<StorePage> searchStore(StoreQuery query);

    List<StoreCategory> getStoreCategories(StoreKind kind);

    CompletableFuture<List<String>> findStoreGameVersions();

    CompletableFuture<Optional<StoreProject>> findStoreProject(StoreQuery browsing, String idOrSlug);

    CompletableFuture<SwitchResult> queueForNextStart(String idOrSlug, String versionId);

    default CompletableFuture<SwitchResult> queueForNextStart(String idOrSlug) {
        return this.queueForNextStart(idOrSlug, "");
    }

    CompletableFuture<SwitchResult> saveToDownloads(StoreQuery browsing, String idOrSlug, String versionId);

    default CompletableFuture<SwitchResult> saveToDownloads(StoreQuery browsing, String idOrSlug) {
        return this.saveToDownloads(browsing, idOrSlug, "");
    }

    void openDownloads();

    CompletableFuture<SwitchResult> fetchPack(StoreKind kind, String idOrSlug, String versionId);

    default CompletableFuture<SwitchResult> fetchPack(StoreKind kind, String idOrSlug) {
        return this.fetchPack(kind, idOrSlug, "");
    }

    StorePackState findPackState(StoreKind kind, String idOrSlug);

    CompletableFuture<SwitchResult> togglePack(StoreKind kind, String idOrSlug);

    List<StoreDownload> getStoreDownloads();

    boolean pauseDownload(int id);

    boolean resumeDownload(int id);

    boolean stopDownload(int id);

    CompletableFuture<Path> writeReport(String why);
}
