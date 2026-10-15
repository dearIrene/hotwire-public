package dev.moon.hotwire.fabric;

import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.plan.ModGraph;
import dev.moon.hotwire.plan.SwitchPlanner;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

public final class ModPolicy {
    static final Set<String> BUILT_IN = Set.of("minecraft", "java", "fabricloader", "mixinextras");

    private static final String MODULE_MARK = "fabric-api:module-lifecycle";

    static final Set<String> NEVER_TOUCHED = Set.of("ixeris", "ferritecore");

    static final Set<String> ALWAYS_ON = Set.of("sodium", "lithium", "phosphor");

    private static volatile SwitchPlanner.NestedJars nestedJars = SwitchPlanner.NestedJars.AUTO;

    private ModPolicy() {
    }

    static boolean isGuarded(String id) {
        return id.equals(HotwireClient.ID) || id.startsWith(HotwireClient.ID + "-") || isFabricApi(id)
                || ALWAYS_ON.contains(id);
    }

    public static boolean isFabricApi(String id) {
        if (id.equals("fabric-api")) {
            return true;
        }
        Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(id);
        if (container.isPresent() && container.get().getMetadata().containsCustomValue(MODULE_MARK)) {
            return true;
        }
        while (container.isPresent()) {
            if (container.get().getMetadata().getId().equals("fabric-api")) {
                return true;
            }
            container = container.get().getContainingMod();
        }
        return false;
    }

    public static boolean isHotwire(String id) {
        return id.equals(HotwireClient.ID) || id.startsWith(HotwireClient.ID + "-");
    }

    public static String describeSelfChange() {
        return "Hotwire can't add, update or turn off itself while it runs: put the new jar in your mods folder and"
                + " restart the game.";
    }

    public static boolean isSwitchable(String id) {
        return !isGuarded(id) && !BUILT_IN.contains(id) && !NEVER_TOUCHED.contains(id);
    }

    public static boolean satisfies(String version, List<String> ranges) {
        try {
            Version parsed = Version.parse(version);
            for (String range : ranges) {
                if (VersionPredicate.parse(range).test(parsed)) {
                    return true;
                }
            }
            return ranges.isEmpty();
        } catch (VersionParsingException e) {
            return true;
        }
    }

    public static ModManifest.Environment getSide() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT
                ? ModManifest.Environment.CLIENT : ModManifest.Environment.SERVER;
    }

    static void setNestedJars(SwitchPlanner.NestedJars choice) {
        nestedJars = choice;
    }

    public static SwitchPlanner makePlannerFor(ModGraph graph) {
        Set<String> guarded = new HashSet<>();
        graph.getIds().stream().filter(ModPolicy::isGuarded).forEach(guarded::add);
        guarded.addAll(findNeededByUnread(graph.getIds()));
        return new SwitchPlanner(graph, new SwitchPlanner.Settings(nestedJars, findBuiltIn(graph.getIds()), guarded,
                ModPolicy::satisfies));
    }

    static Set<String> findNeededByUnread(Set<String> known) {
        Set<String> needed = new HashSet<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            if (known.contains(id) || BUILT_IN.contains(id) || mod.getContainingMod().isPresent()) {
                continue;
            }
            for (ModDependency dependency : mod.getMetadata().getDependencies()) {
                if (dependency.getKind() == ModDependency.Kind.DEPENDS && known.contains(dependency.getModId())) {
                    needed.add(dependency.getModId());
                }
            }
        }
        return needed;
    }

    static Set<String> findBuiltIn(Set<String> known) {
        Set<String> builtIn = new HashSet<>(BUILT_IN);
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            List<String> ids = new ArrayList<>(mod.getMetadata().getProvides());
            ids.add(mod.getMetadata().getId());
            ids.stream().filter(id -> !known.contains(id)).forEach(builtIn::add);
            ModContainer parent = mod.getContainingMod().orElse(null);
            while (parent != null) {
                if (BUILT_IN.contains(parent.getMetadata().getId())) {
                    builtIn.add(mod.getMetadata().getId());
                    break;
                }
                parent = parent.getContainingMod().orElse(null);
            }
        }
        return builtIn;
    }
}
