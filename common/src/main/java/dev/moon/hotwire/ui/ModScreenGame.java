package dev.moon.hotwire.ui;

import dev.moon.hotwire.config.ProfileStore;
import dev.moon.hotwire.modrinth.Downloads;
import dev.moon.hotwire.switching.SwitchHistory;
import dev.moon.hotwire.ui.list.Badges;
import dev.moon.hotwire.ui.list.ModCatalog;
import dev.moon.hotwire.ui.list.ModStatus;
import dev.moon.hotwire.update.PendingChange;
import dev.moon.hotwire.update.UpdateOffer;
import dev.moon.hotwire.update.UrgentUpdate;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

public interface ModScreenGame {
    List<ModStatus> getMods();

    ModCatalog getCatalog();

    Badges getBadges();

    Canvas.@Nullable Image findIcon(String id);

    default List<Downloads.Job> findUpdateDownloads() {
        return List.of();
    }

    Progress getProgress();

    default List<SwitchHistory.Entry> findHistory(String id) {
        return List.of();
    }

    void turnOff(List<String> ids);

    default List<Progress.Entry> previewOff(List<String> ids) {
        return List.of();
    }

    void turnOn(List<String> ids);

    default void prepareAhead(List<String> ids, boolean off) {
    }

    default Set<String> getModsWithSettings() {
        return Set.of();
    }

    default void openStore() {
    }

    default boolean openSettings(String id) {
        return false;
    }

    void applyProfile(String name, ProfileStore.ProfilePlan plan);

    String report();

    default void openLink(String link) {
    }

    default void showReportFile() {
    }

    default @Nullable Path findReportFile() {
        return null;
    }

    default void copyText(String text) {
    }

    void addMod();

    boolean canAdd();

    void updateMod();

    boolean canUpdate();

    void onDone();

    default void cancelReopen() {
    }

    default void updateFromStore(UpdateOffer offer) {
    }

    default void checkForUpdates() {
    }

    default void updateAllFromStore(List<UpdateOffer> offers) {
        offers.forEach(this::updateFromStore);
    }

    default CompletableFuture<AddPreview> dryRunAdd(Path jar) {
        return CompletableFuture.completedFuture(AddPreview.ofRefusal(String.valueOf(jar.getFileName()),
                Lang.get("hotwire.add.not_here")));
    }

    default void add(Path jar, AddMode mode) {
    }

    default CompletableFuture<UpdatePreview> dryRunUpdate(Path jar) {
        return CompletableFuture.completedFuture(new UpdatePreview.Unreadable(String.valueOf(jar.getFileName())));
    }

    default void update(Path jar, UpdateMethod method) {
    }

    default boolean canUpdate(Path jar) {
        return false;
    }

    default List<SettingsSection> getSettingsSections() {
        return List.of();
    }

    default void chooseSetting(String option, int choice) {
    }

    default CacheHost getCaches() {
        return CacheHost.NONE;
    }

    default List<PendingChange> getPendingChanges() {
        return List.of();
    }

    default void cancelPending(String id) throws IOException {
    }

    default void removeCopy(String id) throws IOException {
    }

    default void cleanUpEarlier(String modId) throws IOException {
    }

    default void revertAtNextStart(String id) throws IOException {
    }

    default void turnOffAtNextStart(List<String> ids) {
    }

    default void cancelOffAtNextStart(String id) {
    }

    default void backUpBeforeReopen(boolean backup) {
    }

    default void switchWithReopen(boolean off, List<String> ids) {
    }

    default boolean isFirstOpenAccepted() {
        return true;
    }

    default void acceptFirstOpen() {
    }

    default String findDescription(String id) {
        return "";
    }

    default Set<String> findRestartOnly() {
        return Set.of();
    }

    default List<String> getStayedOn() {
        return List.of();
    }

    default void playSound(LoadingScreen.Cue cue, float pitch) {
    }

    default boolean isTourSeen() {
        return true;
    }

    default void markTourSeen() {
    }

    default String findNewerHotwire() {
        return "";
    }

    default String findHotwireChanges() {
        return "";
    }

    default void updateHotwireAtNextStart() {
    }

    default @Nullable UrgentUpdate findUrgentHotwire() {
        return null;
    }

    default List<String> getSearchPins() {
        return List.of();
    }

    default void setSearchPins(List<String> ids) {
    }

    default List<String> getSearchSetup() {
        return List.of();
    }

    default void setSearchSetup(List<String> ids) {
    }

    default void closeGame() {
    }
}
