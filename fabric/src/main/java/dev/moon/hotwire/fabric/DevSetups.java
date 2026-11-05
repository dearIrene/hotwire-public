package dev.moon.hotwire.fabric;

import dev.moon.hotwire.config.DevSetup;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

final class DevSetups {
    private static final DevSetup FOUND = DevSetup.find(FabricLoader.getInstance().isDevelopmentEnvironment());

    private DevSetups() {
    }

    static DevSetup get() {
        return FOUND;
    }

    static Path getBuildFolder() {
        return FabricLoader.getInstance().getGameDir().resolve("hotwire").resolve("dev");
    }
}
