package dev.moon.hotwire.engine;

import dev.moon.hotwire.config.DevSetup;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public final class DevSetups {
    private static final DevSetup FOUND = DevSetup.find(FabricLoader.getInstance().isDevelopmentEnvironment());

    private DevSetups() {
    }

    public static DevSetup get() {
        return FOUND;
    }

    public static Path getBuildFolder() {
        return FabricLoader.getInstance().getGameDir().resolve("hotwire").resolve("dev");
    }
}
