package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.fabric.LoadedMods;
import dev.moon.hotwire.fabric.ModPolicy;
import dev.moon.hotwire.jar.JarKind;
import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.jar.ModManifestParser;
import dev.moon.hotwire.ui.Lang;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

final class AddFirstLook {
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+(?:\\.\\d+)?");

    private AddFirstLook() {
    }

    static Optional<String> findRefusal(Path file) {
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
        ModManifest manifest = readManifest(file);
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
        return Optional.empty();
    }

    private static @Nullable ModManifest readManifest(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            return entry == null ? null : ModManifestParser.parse(zip.getInputStream(entry).readAllBytes(),
                    String.valueOf(file.getFileName()));
        } catch (IOException | ModJarException | RuntimeException e) {
            return null;
        }
    }

    private static @Nullable String findVersionOf(String id) {
        return LoadedMods.findVersion(id);
    }
}
