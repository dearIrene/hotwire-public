package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.ModPolicy;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.registration.CallerOwner;
import dev.moon.hotwire.registration.RegistrationJournal;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import org.jspecify.annotations.Nullable;

public final class GameRegistrations {
    private static final RegistrationJournal JOURNAL = new RegistrationJournal();
    private static volatile @Nullable CallerOwner owners;
    private static volatile @Nullable ModOfClass modOfClass;
    private static volatile Set<String> never = Set.of();

    private GameRegistrations() {
    }

    public static RegistrationJournal getJournal() {
        return JOURNAL;
    }

    static CallerOwner getOwners() {
        CallerOwner current = owners;
        if (current != null) {
            return current;
        }
        synchronized (GameRegistrations.class) {
            current = owners;
            if (current == null) {
                FabricLoader loader = FabricLoader.getInstance();
                never = findNeverSwitched(loader);
                Set<String> unswitched = never;
                ModOfClass classes = new ModOfClass(loader, unswitched::contains);
                modOfClass = classes;
                current = new CallerOwner(classes::find, never, findLibraries(loader));
                owners = current;
            }
            return current;
        }
    }

    public static @Nullable String findShippedBy(Class<?> type) {
        ModOfClass classes = modOfClass;
        return classes == null ? null : classes.findShippedBy(type);
    }

    public static boolean isOfOffMod(Class<?> type) {
        String mod = findModOf(type);
        return mod != null && Tripwire.isWatched(mod);
    }

    public static @Nullable String findModOf(Class<?> type) {
        getOwners();
        return Objects.requireNonNull(modOfClass).find(type);
    }

    public static @Nullable String findModOrShipper(Class<?> type) {
        String mod = findModOf(type);
        return mod != null ? mod : findShippedBy(type);
    }

    static boolean isNestedLibrary(Class<?> type) {
        String name = type.getName();
        return Objects.equals(type.getClassLoader(), GameRegistrations.class.getClassLoader())
                && !name.startsWith("net.minecraft.") && !name.startsWith("com.mojang.")
                && !name.startsWith("dev.moon.hotwire.");
    }

    static boolean isLibrary(String owner) {
        return !isSwitchable(owner) && !owner.equals("fabricloader") && !owner.equals("minecraft")
                && !owner.equals("java") && !owner.equals("hotwire") && !owner.startsWith("hotwire-");
    }

    public static boolean isSwitchable(@Nullable String mod) {
        getOwners();
        return mod != null && !never.contains(mod);
    }

    public static CallerOwner.@Nullable Owner findOwnerOf(List<CallerOwner.Frame> frames) {
        return getOwners().findFrames(frames);
    }

    public static void learnJar(Path jar, String modId) {
        getOwners();
        ModOfClass classes = Objects.requireNonNull(modOfClass);
        classes.learn(jar, modId);
        synchronized (GameRegistrations.class) {
            FabricLoader loader = FabricLoader.getInstance();
            owners = new CallerOwner(classes::find, findNeverSwitched(loader), findLibraries(loader));
        }
    }

    private static Set<String> findLibraries(FabricLoader loader) {
        Set<String> ids = new HashSet<>();
        for (ModContainer mod : loader.getAllMods()) {
            for (ModDependency dependency : mod.getMetadata().getDependencies()) {
                if (dependency.getKind() == ModDependency.Kind.DEPENDS) {
                    ids.add(dependency.getModId());
                }
            }
        }
        return ids;
    }

    private static Set<String> findNeverSwitched(FabricLoader loader) {
        Set<String> ids = new HashSet<>(Set.of("hotwire", "minecraft", "java", "fabricloader", "mixinextras", "ixeris",
                "ferritecore"));
        for (ModContainer mod : loader.getAllMods()) {
            String id = mod.getMetadata().getId();
            if (ModPolicy.isFabricApi(id)) {
                ids.add(id);
            }
        }
        return ids;
    }
}
