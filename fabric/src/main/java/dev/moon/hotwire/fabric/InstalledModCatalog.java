package dev.moon.hotwire.fabric;

import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.ui.list.ModCatalog;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

final class InstalledModCatalog implements ModCatalog {
    private final InstalledMods installed;
    private final Predicate<String> on;
    private final BooleanSupplier worldLocked;

    InstalledModCatalog(InstalledMods installed, Predicate<String> on, BooleanSupplier worldLocked) {
        this.installed = installed;
        this.on = on;
        this.worldLocked = worldLocked;
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
    public boolean isInstalledByThePlayer(String id) {
        return this.installed.isTopLevel(id);
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
        return this.installed.getGraph().getDependents(id);
    }

    @Override
    public float getStanding(String id) {
        return 0;
    }
}
