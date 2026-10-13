package dev.moon.hotwire.fabric.rebuild;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.version.VersionInterval;
import net.fabricmc.loader.impl.launch.FabricMixinVersions;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.FabricUtil;

public final class MixinDecorations {
    private record Entry(SemanticVersion loader, int mixin) {
    }

    private MixinDecorations() {
    }

    static Map<String, Integer> readCompatibilityByMod(FabricLoader loader) {
        List<Entry> table = readTable();
        Map<String, Integer> levels = new HashMap<>();
        for (ModContainer mod : loader.getAllMods()) {
            levels.put(mod.getMetadata().getId(), findCompatibilityOf(mod, table));
        }
        return levels;
    }

    private static int findCompatibilityOf(ModContainer mod, List<Entry> table) {
        List<VersionInterval> accepted = Collections.singletonList(VersionInterval.INFINITE);
        for (ModDependency dependency : mod.getMetadata().getDependencies()) {
            if (!dependency.getModId().equals("fabricloader") && !dependency.getModId().equals("fabric-loader")) {
                continue;
            }
            if (dependency.getKind() == ModDependency.Kind.DEPENDS) {
                accepted = VersionInterval.and(accepted, dependency.getVersionIntervals());
            } else if (dependency.getKind() == ModDependency.Kind.BREAKS) {
                accepted = VersionInterval.and(accepted, VersionInterval.not(dependency.getVersionIntervals()));
            }
        }
        Version lowest = accepted.isEmpty() ? null : accepted.getFirst().getMin();
        if (lowest != null) {
            for (Entry entry : table) {
                if (lowest.compareTo(entry.loader()) >= 0) {
                    return entry.mixin();
                }
            }
        }
        return FabricUtil.COMPATIBILITY_0_9_2;
    }

    private static List<Entry> readTable() {
        List<Entry> table = new ArrayList<>();
        for (Field field : FabricUtil.class.getFields()) {
            if (!field.getName().startsWith("COMPATIBILITY_") || field.getName().equals("COMPATIBILITY_LATEST")
                    || !Modifier.isStatic(field.getModifiers()) || field.getType() != int.class) {
                continue;
            }
            try {
                int level = field.getInt(null);
                SemanticVersion loaderVersion = parse(FabricMixinVersions.getMinLoaderVersion(level));
                if (loaderVersion != null) {
                    table.add(new Entry(loaderVersion, level));
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("[Hotwire] cannot read Mixin's level " + field.getName(), e);
            }
        }
        table.sort(Comparator.comparing(Entry::loader, Comparator.reverseOrder()));
        return table;
    }

    private static @Nullable SemanticVersion parse(@Nullable String version) {
        if (version == null) {
            return null;
        }
        try {
            return SemanticVersion.parse(version);
        } catch (VersionParsingException e) {
            throw new IllegalStateException("[Hotwire] Fabric Loader names a loader version it cannot read: " + version,
                    e);
        }
    }
}
