package dev.moon.hotwire.engine;

import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.config.HotwireSettings;
import dev.moon.hotwire.dev.DevSession;
import dev.moon.hotwire.modrinth.Downloads;
import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.modrinth.StoreModel;
import dev.moon.hotwire.ui.LoadingScreen;
import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.ui.PromptScreen;
import dev.moon.hotwire.ui.ScreenOpenTimer;
import dev.moon.hotwire.update.UrgentUpdate;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

public interface EngineGame {
    @Nullable Object findOpenScreen();

    Object wrapScreen(Supplier<ModScreen> build, ScreenOpenTimer timer);

    Object wrapScreen(ModScreen screen, ScreenOpenTimer timer);

    void showBack(@Nullable Object back, Collection<String> off);

    void showChat(String line, boolean bad, String hint);

    void playSound(LoadingScreen.Cue cue, float pitch);

    void stopGame();

    @Nullable String findJoinedAddress();

    void tellWorldClosedTo(Consumer<Boolean> listener);

    void setBackupNext(boolean backup);

    boolean isWorldLeftOnly();

    void markClosing();

    void runGameStopping(Set<String> off);

    SettingsScreens makeSettingsScreens(Predicate<String> isOn, Function<String, String> nameOf,
            Consumer<String> sayBad);

    DevControls makeDevControls(HotwireSettings settings, Tasks tasks, DevSession.Engine engine,
            UnaryOperator<@Nullable Object> settingsScreen);

    void chooseJar(Consumer<Path> picked, Runnable unavailable);

    Set<String> findWorldContentKinds(List<String> mods);

    boolean hasEntityHolders(List<String> mods);

    StoreModel.Packs makePacks(ModrinthClient client, Downloads downloads, BiConsumer<String, Boolean> say);

    Object wrapPrompt(PromptScreen prompt);

    void stopEarlyLoads();

    Optional<byte[]> readIconFile(Path jar, String id, Optional<String> declared) throws IOException;

    @Nullable UrgentUpdate findUrgent();

    void tellUrgent(Optional<UrgentUpdate> found);

    boolean showUpdateInChat(String newer, boolean urgent);
}
