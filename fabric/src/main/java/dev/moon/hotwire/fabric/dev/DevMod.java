package dev.moon.hotwire.fabric.dev;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import dev.moon.hotwire.fabric.ModPolicy;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

public record DevMod(String id, String name, List<Path> folders) {
    public DevMod {
        folders = List.copyOf(folders);
    }

    public static List<DevMod> find() {
        List<DevMod> found = new ArrayList<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            ModOrigin origin = mod.getOrigin();
            String id = mod.getMetadata().getId();

            if (origin.getKind() != ModOrigin.Kind.PATH || ModPolicy.isHotwire(id) || !ModPolicy.isSwitchable(id)) {
                continue;
            }
            List<Path> paths = origin.getPaths();
            if (!paths.isEmpty() && paths.stream().allMatch(Files::isDirectory)) {
                found.add(new DevMod(id, mod.getMetadata().getName(), paths));
            }
        }
        return found;
    }
}
