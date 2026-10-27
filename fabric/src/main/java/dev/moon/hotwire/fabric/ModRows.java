package dev.moon.hotwire.fabric;

import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.ui.list.ModList;
import dev.moon.hotwire.ui.list.ModStatus;
import dev.moon.hotwire.update.PendingChange;
import dev.moon.hotwire.update.PendingChanges;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

final class ModRows {
    private final InstalledMods installed;
    private final Predicate<String> isOn;
    private final PendingChanges pending;
    private final Supplier<HotwireEngine.@Nullable JvmParts> parts;

    ModRows(InstalledMods installed, Predicate<String> isOn, PendingChanges pending,
            Supplier<HotwireEngine.@Nullable JvmParts> parts) {
        this.installed = installed;
        this.isOn = isOn;
        this.pending = pending;
        this.parts = parts;
    }

    List<ModStatus> getMods() {
        List<ModStatus> out = new ArrayList<>();
        for (String id : this.installed.getTopLevel()) {
            boolean disabled = this.installed.isDisabled(id);
            (disabled ? this.installed.findDisabled(id) : this.installed.find(id)).ifPresent(mod -> out.add(
                    new ModStatus(id, mod.name(), mod.version(), this.isOn.test(id), String.join(", ", mod.authors()),
                            mod.library(), disabled)));
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    ModList.Badges getBadges() {
        return new ModList.Badges() {
            @Override
            public boolean isDeclaredLibrary(String id) {
                return ModRows.this.installed.find(id).map(ModManifest::library).orElse(false);
            }

            @Override

            public String getEnvironmentOf(String id) {
                return ModRows.this.installed.find(id).map(mod -> switch (mod.environment()) {
                    case CLIENT -> "client";
                    case SERVER -> "server";
                    case BOTH -> mod.entrypoints().containsKey("client") && !mod.entrypoints().containsKey("main")
                            && !mod.entrypoints().containsKey("server") ? "client" : "*";
                }).orElse("*");
            }
        };
    }

    List<PendingChange> getPendingChanges() {
        List<PendingChange> out = new ArrayList<>();
        for (PendingChange change : this.pending.list()) {
            if (!(change instanceof PendingChange.Revertable back) || this.installed.findJarOf(back.modId()) != null) {
                out.add(change);
            }
        }
        HotwireEngine.JvmParts found = this.parts.get();
        if (found != null) {
            out.addAll(found.adds().getCopied());
        }
        return out;
    }

    void removeCopy(String id) throws IOException {
        HotwireEngine.JvmParts found = this.parts.get();
        if (found != null) {
            found.adds().removeCopy(id);
        }
    }

    void revertAtNextStart(String id) throws IOException {
        Path jar = this.installed.findJarOf(id);
        if (jar == null) {
            throw new IllegalStateException(id + " is not installed, so there is nothing to revert.");
        }
        String version = this.installed.find(id).map(ModManifest::version).orElseThrow(
                () -> new IllegalStateException("The running version of " + id + " is not known."));
        this.pending.revert(id, jar, version, System.currentTimeMillis());
    }
}
