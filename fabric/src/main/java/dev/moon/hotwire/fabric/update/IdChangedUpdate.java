package dev.moon.hotwire.fabric.update;

import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.UpdateMethod;
import dev.moon.hotwire.ui.UpdatePreview;
import dev.moon.hotwire.update.UpdatePlan;
import dev.moon.hotwire.update.UpdatePlan.UpdateTier;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import org.jspecify.annotations.Nullable;

final class IdChangedUpdate {
    interface Swapper {
        void queue(String id, String name, Path running, Path incoming, UpdatePlan plan) throws IOException;
    }

    private final ModUpdater.Engine engine;
    private final Swapper swapper;

    IdChangedUpdate(ModUpdater.Engine engine, Swapper swapper) {
        this.engine = engine;
        this.swapper = swapper;
    }

    @Nullable String findReplacedBy(ModManifest incoming) {
        for (String provided : incoming.provides()) {
            if (this.engine.findRunningJarOf(provided) != null) {
                return provided;
            }
        }
        for (String id : this.engine.getGraph().getIds()) {
            ModManifest installed = this.engine.getGraph().find(id).orElse(null);
            if (installed != null && installed.name().equalsIgnoreCase(incoming.name())
                    && this.engine.findRunningJarOf(id) != null) {
                return id;
            }
        }
        return null;
    }

    UpdatePreview preview(ModManifest incoming, String replaced, String fileName, Path jar) {
        String refusal = this.findRefusal(incoming, replaced);
        if (refusal != null) {
            return new UpdatePreview.Refused(incoming.name(), refusal);
        }
        return UpdatePreview.Ready.of(incoming.name(), this.engine.findIcon(replaced, jar),
                this.findInstalledVersion(replaced), incoming.version(), fileName, UpdateMethod.NEXT_START,
                List.of(Lang.get("hotwire.update.new_id", replaced, incoming.id())));
    }

    ModUpdater.Result update(ModManifest incoming, String replaced, Path jar, UpdateMethod method) {
        String refusal = this.findRefusal(incoming, replaced);
        if (refusal != null) {
            return new ModUpdater.Result.Refused(refusal);
        }
        if (method != UpdateMethod.NEXT_START) {
            return new ModUpdater.Result.Refused(Lang.get("hotwire.update.new_id", replaced, incoming.id()));
        }
        Path running = Objects.requireNonNull(this.engine.findRunningJarOf(replaced));
        UpdatePlan plan = new UpdatePlan(replaced, this.findInstalledVersion(replaced), incoming.version(),
                UpdateTier.NEXT_START, List.of(), List.of(), List.of());
        try {
            this.swapper.queue(replaced, incoming.name(), running, jar, plan);
            return new ModUpdater.Result.Queued(replaced, incoming.name(), plan);
        } catch (IOException e) {
            return new ModUpdater.Result.Failed(incoming.name() + " could not be queued for the next launch: "
                    + e.getMessage(), e);
        }
    }

    private @Nullable String findRefusal(ModManifest incoming, String replaced) {
        SortedSet<String> dependents = this.engine.getGraph().getDependents(replaced);
        return dependents.isEmpty() ? null
                : Lang.get("hotwire.update.new_id_needed", incoming.name(), incoming.id(),
                        String.join(", ", dependents));
    }

    private String findInstalledVersion(String id) {
        return this.engine.getGraph().find(id).map(ModManifest::version).orElse("");
    }
}
