package dev.moon.hotwire.seam;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public interface SwitchUnits {
    void readModelsAhead(Collection<String> mods);

    void readModelsAheadUnlessStanding(Collection<String> mods);

    void unwrapModels(Collection<String> mods);

    void takeRegistryKeysOnce();

    void checkRegistriesAfter(Set<String> switched);

    Map<String, List<String>> findWorldContent(Collection<String> mods);

    default boolean canContentLeave(Map<String, List<String>> content) {
        return false;
    }

    default void removeContent(Collection<String> mods) {
    }

    default boolean isContentOut(Collection<String> mods) {
        return false;
    }

    default void restoreContent(Collection<String> mods) {
    }

    Map<String, String> findEntityHolders(Collection<String> mods, Predicate<Object> reached);

    void rememberPluginAnswers(Collection<String> mods);

    void takeOutPlugins(Collection<String> mods);

    void putBackPlugins(Collection<String> mods);

    void takeOutSounds(Collection<String> mods);

    void takeOutReloadedLists(Collection<String> mods);

    void chooseCursorAgainFor(Set<String> patched);

    void takeOutHuds(Collection<String> mods);

    void takeOutNetworkHandlers(Collection<String> mods);

    void takeOutRendererProviders(Collection<String> mods);

    boolean takeRenderersChanged();

    void takeOutTasks(Collection<String> mods);

    void takeOutTasksAgain(Collection<String> mods);

    void putBackTasks(Collection<String> mods);

    void markCommandsGoingOff(Predicate<String> off);

    void takeOutCommands(Predicate<String> off);

    void onCommandsWatched();

    void takeOutWorldWrappers(Collection<String> mods);

    void takeOutGlfwCallbacks(Collection<String> mods);

    void takeOutGameModes(Collection<String> mods);

    void takeOutAttachments(Collection<String> mods);

    void giveBackAttachments(Collection<String> mods);

    void clearParticlesOf(Collection<String> mods);

    void putBackTakes(Collection<String> mods, Predicate<String> on);

    void compileShadersAgainFor(Collection<String> changed);

    void rebuildPipelinesHolding(Collection<String> mods);

    void redrawChunks();

    List<String> closeViews(Collection<String> mods);
}
