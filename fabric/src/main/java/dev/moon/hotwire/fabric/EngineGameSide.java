package dev.moon.hotwire.fabric;

import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.config.HotwireSettings;
import dev.moon.hotwire.config.ProfileStore;
import dev.moon.hotwire.dev.DevSession;
import dev.moon.hotwire.engine.DevControls;
import dev.moon.hotwire.engine.EngineGame;
import dev.moon.hotwire.engine.SettingsScreens;
import dev.moon.hotwire.fabric.add.WorldReopen;
import dev.moon.hotwire.fabric.registration.ModLifecycle;
import dev.moon.hotwire.fabric.registration.WorldContent;
import dev.moon.hotwire.fabric.screen.HotwireScreen;
import dev.moon.hotwire.fabric.screen.ModFileChooser;
import dev.moon.hotwire.fabric.screen.ModIcons;
import dev.moon.hotwire.fabric.screen.PromptGameScreen;
import dev.moon.hotwire.fabric.store.PackFolders;
import dev.moon.hotwire.modrinth.Downloads;
import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.modrinth.StoreModel;
import dev.moon.hotwire.registration.GameRegistrations;
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
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import org.jspecify.annotations.Nullable;

final class EngineGameSide implements EngineGame {
    @Override
    public @Nullable Object findOpenScreen() {
        return ModViews.openScreen();
    }

    @Override
    public Object wrapScreen(Supplier<ModScreen> build, ScreenOpenTimer timer) {
        return new HotwireScreen(build, timer);
    }

    @Override
    public Object wrapScreen(ModScreen screen, ScreenOpenTimer timer) {
        return new HotwireScreen(screen, timer);
    }

    @Override
    public void showBack(@Nullable Object back, Collection<String> off) {
        ModViews.showBack((Screen) back, off);
    }

    @Override
    public void showChat(String line, boolean bad, String hint) {
        ModViews.showChat(line, bad, hint);
    }

    @Override
    public void playSound(LoadingScreen.Cue cue, float pitch) {
        SoundEvent sound = switch (cue) {
            case ONE_OFF -> SoundEvents.ELDER_GUARDIAN_CURSE;
            case ONE_ON, MOD -> SoundEvents.EXPERIENCE_ORB_PICKUP;
            case BATCH_END -> SoundEvents.PLAYER_LEVELUP;
            case ADDED -> SoundEvents.END_PORTAL_FRAME_FILL;
        };
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch,
                cue == LoadingScreen.Cue.MOD ? 0.35f : 0.6f));
    }

    @Override
    public void stopGame() {
        Minecraft.getInstance().stop();
    }

    @Override
    public @Nullable String findJoinedAddress() {
        Minecraft game = Minecraft.getInstance();
        ServerData server = game.getCurrentServer();
        if (server != null) {
            return server.ip;
        }
        return game.hasSingleplayerServer() ? ProfileStore.SINGLEPLAYER : null;
    }

    @Override
    public void tellWorldClosedTo(Consumer<Boolean> listener) {
        WorldReopen.tellClosedTo(listener);
    }

    @Override
    public void setBackupNext(boolean backup) {
        WorldReopen.setBackupNext(backup);
    }

    @Override
    public boolean isWorldLeftOnly() {
        return WorldReopen.isLeftOnly();
    }

    @Override
    public void markClosing() {
        CrashReports.markClosing();
    }

    @Override
    public void runGameStopping(Set<String> off) {
        ModLifecycle.onGameStopping(off, GameRegistrations.getJournal(), Minecraft.getInstance());
    }

    @Override
    public SettingsScreens makeSettingsScreens(Predicate<String> isOn, Function<String, String> nameOf,
            Consumer<String> sayBad) {
        return new ConfigScreens(isOn, nameOf, sayBad);
    }

    @Override
    public DevControls makeDevControls(HotwireSettings settings, Tasks tasks, DevSession.Engine engine,
            UnaryOperator<@Nullable Object> settingsScreen) {
        return new DevMode(settings, tasks, engine, settingsScreen);
    }

    @Override
    public void chooseJar(Consumer<Path> picked, Runnable unavailable) {
        ModFileChooser.choose(picked, unavailable);
    }

    @Override
    public Set<String> findWorldContentKinds(List<String> mods) {
        Set<String> kinds = new TreeSet<>();
        WorldContent.find(mods).values().forEach(kinds::addAll);
        return kinds;
    }

    @Override
    public StoreModel.Packs makePacks(ModrinthClient client, Downloads downloads, BiConsumer<String, Boolean> say) {
        return new PackFolders(client, downloads, say);
    }

    @Override
    public void tellUrgent(Optional<UrgentUpdate> found) {
        UrgentNotice.onChecked(found);
    }

    @Override
    public boolean showUpdateInChat(String newer, boolean urgent) {
        if (Minecraft.getInstance().player == null) {
            return false;
        }
        if (urgent) {
            UpdateChat.showUrgent(newer);
        } else {
            UpdateChat.show(newer);
        }
        return true;
    }

    @Override
    public Object wrapPrompt(PromptScreen prompt) {
        return new PromptGameScreen(prompt);
    }

    @Override
    public void stopEarlyLoads() {
        EarlyDefinitions.stop();
    }

    @Override
    public Optional<byte[]> readIconFile(Path jar, String id, Optional<String> declared) throws IOException {
        return ModIcons.readFile(jar, id, declared);
    }

    @Override
    public @Nullable UrgentUpdate findUrgent() {
        return UrgentNotice.find();
    }
}
