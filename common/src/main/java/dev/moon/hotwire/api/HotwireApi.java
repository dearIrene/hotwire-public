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

    CompletableFuture<Path> writeReport(String why);
}
