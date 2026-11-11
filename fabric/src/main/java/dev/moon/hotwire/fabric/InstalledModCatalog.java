package dev.moon.hotwire.fabric;

import dev.moon.hotwire.config.HotwireSettings;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.plan.ModGraph;
import dev.moon.hotwire.plan.SwitchPlanner;
import dev.moon.hotwire.ui.list.ModCatalog;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

final class InstalledModCatalog implements ModCatalog {
    private final InstalledMods installed;
    private final Predicate<String> on;
    private final BooleanSupplier worldLocked;
    private final HotwireSettings settings;

    InstalledModCatalog(InstalledMods installed, Predicate<String> on, BooleanSupplier worldLocked,
            HotwireSettings settings) {
        this.installed = installed;
        this.on = on;
        this.worldLocked = worldLocked;
        this.settings = settings;
    }

    @Override
    public boolean isFavorite(String id) {
        return this.settings.isFavorite(id);
    }

    @Override
    public void setFavorite(String id, boolean favorite) {
        this.settings.setFavorite(id, favorite);
    }

    @Override
    public boolean isOn(String id) {
        return this.on.test(id);
    }

    @Override
    public boolean isLibrary(String id) {
        return this.installed.find(id).map(ModManifest::library).orElse(false);
    }

    @Override
    public boolean isHidden(String id) {
        return ModPolicy.BUILT_IN.contains(id) || !this.installed.isTopLevel(id);
    }

    @Override
    public Set<String> getContainersOf(String id) {
        return this.installed.getGraph().getContainers(id).stream().filter(this.on).collect(Collectors.toSet());
    }

    @Override
    public boolean isProtected(String id) {
        return ModPolicy.isGuarded(id);
    }

    @Override
    public boolean isLocked(String id) {
        return this.worldLocked.getAsBoolean();
    }

    @Override
    public String name(String id) {
        return this.installed.nameOf(id);
    }

    @Override
    public String getAuthors(String id) {
        return this.installed.find(id).map(mod -> String.join(", ", mod.authors())).orElse("");
    }

    @Override
    public Set<String> getNeededBy(String id) {
        ModGraph graph = this.installed.getGraph();
        if (this.planned != graph) {
            this.planner = ModPolicy.makePlannerFor(graph);
            this.planned = graph;
        }
        Set<String> off = graph.getIds().stream().filter(one -> !this.on.test(one)).collect(Collectors.toSet());
        Set<String> out = new TreeSet<>(this.planner.planOff(List.of(id), off).order());
        out.remove(id);
        return out;
    }

    private @Nullable ModGraph planned;
    private SwitchPlanner planner = ModPolicy.makePlannerFor(new ModGraph(List.of()));

    @Override
    public float getStanding(String id) {
        return 0;
    }
}
