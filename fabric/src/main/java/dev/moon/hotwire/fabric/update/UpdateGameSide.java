package dev.moon.hotwire.fabric.update;

import dev.moon.hotwire.fabric.GameParts;
import dev.moon.hotwire.fabric.add.AddAssets;
import dev.moon.hotwire.fabric.add.LaunchSteps;
import dev.moon.hotwire.update.UpdateGame;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class UpdateGameSide implements UpdateGame {
    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateGameSide.class);

    @Override
    public Runnable takeOutOldPayloads(Set<String> mods) {
        return GameParts.getPayloads().takeOut(mods);
    }

    @Override
    public void reloadForUpdated(Map<String, Path> jars, Map<String, Path> running, Runnable then) {
        AddAssets.reloadForUpdated(jars, running, then);
    }

    @Override
    public void runLaunchSteps(List<String> ids, Function<String, @Nullable Class<?>> findLoaded) {
        try {
            Minecraft.getInstance().submit(() -> LaunchSteps.run("the update of " + ids,
                    LaunchSteps.planAfterStart(ids, findLoaded, new LinkedHashMap<>()),
                    Set.of("host lists", "entrypoint lists", "client-started listeners"))).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("[Hotwire] the launch steps of {} did not run after their update", ids, e);
        }
    }
}
