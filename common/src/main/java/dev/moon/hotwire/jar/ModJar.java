package dev.moon.hotwire.jar;

import java.util.List;
import java.util.Optional;

public record ModJar(String name, ModManifest manifest, ModPatches patches, List<ModJar> nested,
        List<String> nestedNotMods,
        Optional<AccessWidenerText> accessWidener) {
    public ModJar {
        nested = List.copyOf(nested);
        nestedNotMods = List.copyOf(nestedNotMods);
    }
}
