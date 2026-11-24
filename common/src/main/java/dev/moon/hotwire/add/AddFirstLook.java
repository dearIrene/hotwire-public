package dev.moon.hotwire.add;

import dev.moon.hotwire.engine.LoadedMods;
import dev.moon.hotwire.engine.ModPolicy;
import dev.moon.hotwire.jar.JarKind;
import dev.moon.hotwire.jar.JarLookup;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.ui.Lang;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ModDependency;
import org.jspecify.annotations.Nullable;

public final class AddFirstLook {
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+(?:\\.\\d+)?");

    private AddFirstLook() {
    }

    public static Optional<String> findRefusal(Path file) {
        String name = String.valueOf(file.getFileName()).replaceFirst("(?i)\\.(jar|zip)$", "");
        JarKind kind = JarKind.of(file);
        @Nullable String other = switch (kind) {
            case FABRIC -> null;
            case FORGE -> Lang.get("hotwire.add.other_loader", name, "Forge");
            case NEOFORGE -> Lang.get("hotwire.add.other_loader", name, "NeoForge");
            case QUILT -> Lang.get("hotwire.add.other_loader", name, "Quilt");
            case RESOURCE_PACK -> Lang.get("hotwire.add.resource_pack", name);
            case SHADER_PACK -> Lang.get("hotwire.add.shader_pack", name);
            case DATA_PACK -> Lang.get("hotwire.add.data_pack", name);
            case NOT_A_MOD -> Lang.get("hotwire.add.not_a_mod", name);
            case NOT_A_ZIP -> Lang.get("hotwire.add.not_a_jar", name);
        };
        if (other != null) {
            return Optional.of(other);
        }
        ModManifest manifest = JarLookup.findManifest(file);
        return manifest == null ? Optional.empty() : findForThisGame(manifest);
    }

    private static Optional<String> findForThisGame(ModManifest manifest) {
        Map<String, List<String>> needs = manifest.relations().getOrDefault(ModManifest.Relation.DEPENDS, Map.of());
        String game = findVersionOf("minecraft");
        List<String> minecraft = needs.getOrDefault("minecraft", List.of());
        if (game != null && !ModPolicy.satisfies(game, minecraft)) {
            Matcher wanted = VERSION.matcher(String.join(" ", minecraft));
            String made = wanted.find() ? wanted.group() : String.join(" or ", minecraft);
            return Optional.of(Lang.get("hotwire.add.other_minecraft", manifest.name(), made, game));
        }
        String loader = findVersionOf("fabricloader");
        if (loader != null && !ModPolicy.satisfies(loader, needs.getOrDefault("fabricloader", List.of()))) {
            return Optional.of(Lang.get("hotwire.add.newer_loader", manifest.name()));
        }
        if (manifest.environment() == ModManifest.Environment.SERVER) {
            return Optional.of(Lang.get("hotwire.add.server_only", manifest.name()));
        }
        String[] broken = findBroken(manifest, AddFirstLook::findVersionOf);
        broken = broken != null ? broken : findBreaking(manifest);
        return broken == null ? Optional.empty()
                : Optional.of(Lang.get("hotwire.add.breaks", broken[0], broken[1], broken[2]));
    }

    static String @Nullable [] findBroken(ModManifest manifest, Function<String, @Nullable String> versionOf) {
        for (Map.Entry<String, List<String>> breaks : manifest.getRelation(ModManifest.Relation.BREAKS).entrySet()) {
            String has = versionOf.apply(breaks.getKey());
            if (has != null && ModPolicy.satisfies(has, breaks.getValue())) {
                return new String[] {manifest.name(), breaks.getKey(), has};
            }
        }
        return null;
    }

    private static String @Nullable [] findBreaking(ModManifest manifest) {
        Version version;
        try {
            version = Version.parse(manifest.version());
        } catch (VersionParsingException e) {
            return null;
        }
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            for (ModDependency dependency : mod.getMetadata().getDependencies()) {
                if (dependency.getKind() == ModDependency.Kind.BREAKS && dependency.matches(version)
                        && (dependency.getModId().equals(manifest.id())
                        || manifest.provides().contains(dependency.getModId()))) {
                    return new String[] {mod.getMetadata().getName(), manifest.name(), manifest.version()};
                }
            }
        }
        return null;
    }

    private static @Nullable String findVersionOf(String id) {
        return LoadedMods.findVersion(id);
    }
}
