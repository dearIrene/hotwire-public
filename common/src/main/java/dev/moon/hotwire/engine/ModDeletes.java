package dev.moon.hotwire.engine;

import dev.moon.hotwire.plan.ModGraph;
import dev.moon.hotwire.rebuild.KnotInternals;
import dev.moon.hotwire.switching.PlayerMessages;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.update.PendingChange;
import dev.moon.hotwire.update.PendingChanges;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ModDeletes {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModDeletes.class);

    private final InstalledMods installed;
    private final DisabledMods disabled;
    private final PendingChanges pending;
    private final Supplier<@Nullable JvmParts> parts;
    private final Path copies;

    ModDeletes(InstalledMods installed, DisabledMods disabled, PendingChanges pending,
            Supplier<@Nullable JvmParts> parts, Path copies) {
        this.installed = installed;
        this.disabled = disabled;
        this.pending = pending;
        this.parts = parts;
        this.copies = copies;
    }

    String delete(String id) {
        this.disabled.awaitRenames();
        String name = this.installed.nameOf(id);
        Path jar = this.installed.findFolderJarOf(id);
        if (jar == null) {
            return Lang.get("hotwire.switch.not_installed", name);
        }
        List<String> needing = this.findNeeding(id);
        if (!needing.isEmpty()) {
            return Lang.get("hotwire.delete.needed", name, PlayerMessages.joinNames(needing, this.installed::nameOf));
        }
        String where;
        try {
            if (!this.installed.isDisabledSinceStart(id) && this.installed.isRunningFromFolderJar(id)) {
                this.readFromCopy(jar);
            }
            where = JarBin.throwAway(jar);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[Hotwire] {} could not go to the bin", jar.getFileName(), e);
            return Lang.get("hotwire.delete.failed", name, String.valueOf(e.getMessage()));
        }
        this.dropChanges(id);
        this.installed.onDeleted(id);
        LOGGER.info("[Hotwire] {} is deleted: {} went to {}", id, jar.getFileName(), where);
        return "";
    }

    private List<String> findNeeding(String id) {
        ModGraph graph = this.installed.getGraph();
        Set<String> staying = new HashSet<>();
        for (String other : this.installed.getTopLevel()) {
            if (!other.equals(id) && !this.installed.isGoneAtNextStart(other)) {
                staying.add(other);
            }
        }
        Set<String> going = graph.findCarriedOnlyBy(id, staying);
        return staying.stream().filter(other -> !Collections.disjoint(graph.getUses(other), going)).sorted().toList();
    }

    private void readFromCopy(Path jar) throws IOException {
        Files.createDirectories(this.copies);
        Path copy = Files.copy(jar, Files.createTempFile(this.copies, "", "-" + jar.getFileName()),
                StandardCopyOption.REPLACE_EXISTING);
        FabricLauncherBase.getLauncher().addToClassPath(copy);
        KnotInternals.dropFromClassPath(jar);
    }

    private void dropChanges(String id) {
        try {
            for (PendingChange change : this.pending.list()) {
                if (change instanceof PendingChange.Update && change.modId().equals(id)) {
                    this.pending.cancel(id);
                }
            }
            JvmParts found = this.parts.get();
            if (found != null) {
                found.adds().removeCopy(id);
            }
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] what waited for {} at the close could not be dropped", id, e);
        }
    }
}
