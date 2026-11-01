package dev.moon.hotwire.fabric.update;

import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.switching.SwitchTimes;
import dev.moon.hotwire.ui.UpdateMethod;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class UpdateBatch {
    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateBatch.class);

    private final ModUpdater updater;
    private final LiveUpdates live;

    UpdateBatch(ModUpdater updater, LiveUpdates live) {
        this.updater = updater;
        this.live = live;
    }

    List<ModUpdater.Result> run(List<Path> jars, Function<Path, ModUpdater.Result> alone,
            BiFunction<Path, ModUpdater.Result.Failed, ModUpdater.Result> instead) {
        ModUpdater.Result[] results = new ModUpdater.Result[jars.size()];
        Map<String, ModManifest> brought = new HashMap<>();
        Map<String, String> waiting = new HashMap<>();
        List<ModUpdater.Sorted.Planned> inPlace = new ArrayList<>();
        List<Integer> inPlaceAt = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        List<Integer> later = new ArrayList<>();
        for (int i = 0; i < jars.size(); i++) {
            switch (this.updater.sort(jars.get(i), brought, waiting)) {
                case ModUpdater.Sorted.Refused refused -> results[i] = refused.result();
                case ModUpdater.Sorted.IdChanged changed -> later.add(i);
                case ModUpdater.Sorted.Planned planned when planned.method() == UpdateMethod.NEXT_START -> {
                    results[i] = this.updater.take(planned, UpdateMethod.NEXT_START);
                    if (results[i] instanceof ModUpdater.Result.Queued) {
                        waiting.put(planned.manifest().id(), planned.manifest().version());
                    }
                }
                case ModUpdater.Sorted.Planned planned -> {
                    Set<String> ids = new HashSet<>(List.of(planned.manifest().id()));
                    planned.whole().bundled().forEach(carried -> ids.add(carried.manifest().id()));
                    if (Collections.disjoint(ids, taken)) {
                        taken.addAll(ids);
                        inPlace.add(planned);
                        inPlaceAt.add(i);
                        brought.putAll(this.updater.findComing(planned.read()));
                    } else {
                        later.add(i);
                    }
                }
            }
        }
        SwitchTimes.mark("updates: sorted");
        LOGGER.info("[Hotwire] of {} updates, {} go in now together, {} wait for the next start, {} go on their own"
                + " and the rest are refused", jars.size(), inPlace.size(), waiting.size(), later.size());
        if (!inPlace.isEmpty()) {
            List<ModUpdater.Result> made = this.live.apply(inPlace);
            boolean refusedTogether = inPlace.size() > 1
                    && made.stream().allMatch(ModUpdater.Result.Failed.class::isInstance);
            if (refusedTogether) {
                LOGGER.info("[Hotwire] the {} updates in place could not go in together; each goes on its own",
                        inPlace.size());
            }
            for (int i = 0; i < made.size(); i++) {
                int at = inPlaceAt.get(i);
                results[at] = refusedTogether ? alone.apply(jars.get(at))
                        : made.get(i) instanceof ModUpdater.Result.Failed failed ? instead.apply(jars.get(at), failed)
                        : made.get(i);
            }
        }
        for (int at : later) {
            results[at] = alone.apply(jars.get(at));
        }
        return List.of(results);
    }
}
