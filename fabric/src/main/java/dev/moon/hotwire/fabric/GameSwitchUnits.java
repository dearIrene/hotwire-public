package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.rebuild.LivePluginShapes;
import dev.moon.hotwire.fabric.registration.EntityAttachments;
import dev.moon.hotwire.fabric.registration.EntityHolders;
import dev.moon.hotwire.fabric.registration.EntityRendererProviders;
import dev.moon.hotwire.fabric.registration.GameHold;
import dev.moon.hotwire.fabric.registration.GameTasks;
import dev.moon.hotwire.fabric.registration.GlfwCallbacks;
import dev.moon.hotwire.fabric.registration.MixinPlugins;
import dev.moon.hotwire.fabric.registration.ModSounds;
import dev.moon.hotwire.fabric.registration.ModelReloadCheck;
import dev.moon.hotwire.fabric.registration.NetworkHandlers;
import dev.moon.hotwire.fabric.registration.OffModCommands;
import dev.moon.hotwire.fabric.registration.OneConfigHuds;
import dev.moon.hotwire.fabric.registration.RegistryCheck;
import dev.moon.hotwire.fabric.registration.ReloadedLists;
import dev.moon.hotwire.fabric.registration.WorldContent;
import dev.moon.hotwire.fabric.registration.WorldWrappers;
import dev.moon.hotwire.fabric.version.WindowCursor;
import dev.moon.hotwire.registration.GameRegistrations;
import dev.moon.hotwire.seam.SwitchUnits;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;

final class GameSwitchUnits implements SwitchUnits {
    private final MixinPlugins plugins;
    private final RegistryCheck registries = new RegistryCheck();
    private final WorldWrappers worldWrappers = new WorldWrappers();

    GameSwitchUnits(MixinPlugins plugins) {
        this.plugins = plugins;
    }

    @Override
    public void readModelsAhead(Collection<String> mods) {
        ModelReloadCheck.readAhead(mods);
    }

    @Override
    public void readModelsAheadUnlessStanding(Collection<String> mods) {
        ModelReloadCheck.readAheadUnlessStanding(mods);
    }

    @Override
    public void unwrapModels(Collection<String> mods) {
        ModelReloadCheck.unwrapModels(mods);
    }

    @Override
    public void takeRegistryKeysOnce() {
        this.registries.takeKeysOnce();
    }

    @Override
    public void checkRegistriesAfter(Set<String> switched) {
        this.registries.checkAfter(switched);
    }

    @Override
    public Map<String, List<String>> findWorldContent(Collection<String> mods) {
        return WorldContent.find(mods);
    }

    @Override
    public Map<String, String> findEntityHolders(Collection<String> mods, Predicate<Object> reached) {
        return EntityHolders.find(mods, reached);
    }

    @Override
    public void rememberPluginAnswers(Collection<String> mods) {
        LivePluginShapes.rememberAnswers(mods);
    }

    @Override
    public void takeOutPlugins(Collection<String> mods) {
        this.plugins.takeOut(mods);
    }

    @Override
    public void putBackPlugins(Collection<String> mods) {
        this.plugins.putBack(mods);
    }

    @Override
    public void takeOutSounds(Collection<String> mods) {
        ModSounds.takeOut(mods);
    }

    @Override
    public void takeOutReloadedLists(Collection<String> mods) {
        ReloadedLists.takeOut(mods);
    }

    @Override
    public void chooseCursorAgainFor(Set<String> patched) {
        WindowCursor.chooseAgainFor(patched);
    }

    @Override
    public void takeOutHuds(Collection<String> mods) {
        OneConfigHuds.takeOut(mods);
    }

    @Override
    public void takeOutNetworkHandlers(Collection<String> mods) {
        NetworkHandlers.takeOut(mods);
    }

    @Override
    public void takeOutRendererProviders(Collection<String> mods) {
        EntityRendererProviders.takeOut(mods);
    }

    @Override
    public boolean takeRenderersChanged() {
        return EntityRendererProviders.takeChanged();
    }

    @Override
    public void takeOutTasks(Collection<String> mods) {
        GameTasks.takeOut(mods);
    }

    @Override
    public void takeOutTasksAgain(Collection<String> mods) {
        GameTasks.takeOutAgain(mods);
    }

    @Override
    public void putBackTasks(Collection<String> mods) {
        GameTasks.onBack(mods);
    }

    @Override
    public void markCommandsGoingOff(Predicate<String> off) {
        OffModCommands.markGoingOff(off);
    }

    @Override
    public void takeOutCommands(Predicate<String> off) {
        OffModCommands.takeOut(off);
    }

    @Override
    public void onCommandsWatched() {
        OffModCommands.onWatched();
    }

    @Override
    public void takeOutWorldWrappers(Collection<String> mods) {
        this.worldWrappers.takeOut(mods);
    }

    @Override
    public void takeOutGlfwCallbacks(Collection<String> mods) {
        GlfwCallbacks.takeOut(mods);
    }

    @Override
    public void takeOutAttachments(Collection<String> mods) {
        EntityAttachments.takeOut(mods);
    }

    @Override
    public void giveBackAttachments(Collection<String> mods) {
        EntityAttachments.giveBack(mods);
    }

    @Override
    public void clearParticlesOf(Collection<String> mods) {
        ModViews.clearParticlesOf(mods);
    }

    @Override
    public void putBackTakes(Collection<String> mods, Predicate<String> on) {
        GameRegistrations.getJournal().putBackTakes(mods, on, GameHold::run);
    }

    @Override
    public void compileShadersAgainFor(Collection<String> changed) {
        if (ShaderPrograms.isChangedBy(changed)) {
            GameHold.onGameAndWait(() -> ShaderPrograms.compileAgain(Minecraft.getInstance()));
        }
    }

    @Override
    public void redrawChunks() {
        ChunkRedraw.run();
    }

    @Override
    public List<String> closeViews(Collection<String> mods) {
        return ModViews.close(mods);
    }
}
