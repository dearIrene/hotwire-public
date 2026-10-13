package dev.moon.hotwire.fabric;

import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.plan.ModGraph;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InstalledMods {
    private static final Logger LOGGER = LoggerFactory.getLogger(InstalledMods.class);
    private ModGraph graph;
    private final List<ModJar> jars;

    private final List<ModJar> nestedAdded = new ArrayList<>();

    private final Map<String, Path> jarOf = new HashMap<>();
    private final Set<String> topLevel = new HashSet<>();
    private final Set<String> addedThisSession = new HashSet<>();
    private final Set<String> updatedThisSession = new HashSet<>();

    public InstalledMods(List<ModJar> jars, Map<String, Path> jarOf, Set<String> topLevel) {
        this.jars = new ArrayList<>(jars);
        this.jarOf.putAll(jarOf);
        this.topLevel.addAll(topLevel);
        for (ModJar jar : jars) {
            for (String target : jar.patches().patches()) {
                if (target.startsWith("dev/moon/hotwire/")) {
                    LOGGER.warn("[Hotwire] {} patches Hotwire's own {}; what Hotwire does with it on is that mod's",
                            jar.manifest().id(), target);
                }
            }
        }
        this.graph = new ModGraph(this.jars);
        this.graph.getIds().forEach(id -> {
            List<String> called = this.graph.getCalls(id).stream().filter(ModPolicy::isSwitchable)
                    .filter(this.graph::isInstalledOnItsOwn).toList();
            if (!called.isEmpty()) {
                LOGGER.info("[Hotwire] {}'s code calls {}, which its manifest does not name; it goes off with them",
                        id, called);
            }
        });
    }

    public synchronized ModGraph getGraph() {
        return this.graph;
    }

    synchronized List<ModJar> getGraphJars() {
        List<ModJar> all = new ArrayList<>(this.jars);
        all.addAll(this.nestedAdded);
        return List.copyOf(all);
    }

    public synchronized Optional<ModManifest> find(String id) {
        return this.graph.find(id);
    }

    public synchronized boolean isInstalled(String id) {
        return this.graph.find(id).isPresent();
    }

    public synchronized @Nullable Path findJarOf(String id) {
        return this.jarOf.get(id);
    }

    public synchronized boolean isTopLevel(String id) {
        return this.topLevel.contains(id);
    }

    synchronized SortedSet<String> getTopLevel() {
        return new TreeSet<>(this.topLevel);
    }

    synchronized String nameOf(String id) {
        return this.graph.find(id).map(ModManifest::name).orElse(id);
    }

    public synchronized ModGraph makeGraphWith(Collection<ModJar> more) {
        Set<String> inside = new HashSet<>();
        more.forEach(jar -> addNestedIds(jar, inside));
        List<ModJar> all = new ArrayList<>(this.jars);
        more.stream().filter(jar -> !inside.contains(jar.manifest().id())).forEach(all::add);
        return new ModGraph(all);
    }

    private static void addNestedIds(ModJar jar, Set<String> ids) {
        for (ModJar nested : jar.nested()) {
            ids.add(nested.manifest().id());
            addNestedIds(nested, ids);
        }
    }

    public synchronized void onAdded(Path jar, ModJar read, Collection<String> ids) {
        this.jars.add(read);
        for (ModJar nested : read.nested()) {
            if (ids.contains(nested.manifest().id())) {
                this.nestedAdded.add(nested);
            }
        }
        this.graph = new ModGraph(this.jars);
        this.topLevel.add(read.manifest().id());
        this.jarOf.put(read.manifest().id(), jar);
        this.addedThisSession.addAll(ids);
    }

    public synchronized void onUpdated(String id, Path jar, ModJar read) {
        this.jars.removeIf(old -> old.manifest().id().equals(id));
        this.jars.add(read);
        this.graph = new ModGraph(this.jars);
        this.jarOf.put(id, jar);
        this.updatedThisSession.add(id);
    }

    public synchronized boolean isAddedThisSession(String id) {
        return this.addedThisSession.contains(id);
    }

    public synchronized boolean isUpdatedThisSession(String id) {
        return this.updatedThisSession.contains(id);
    }

    static @Nullable Path findLoadedFrom(ModContainer container) {
        if (container.getOrigin().getKind() != ModOrigin.Kind.PATH || container.getOrigin().getPaths().size() != 1) {
            return null;
        }
        Path path = container.getOrigin().getPaths().getFirst().toAbsolutePath().normalize();
        boolean inMods = path.getParent() != null && path.getParent().getFileName() != null
                && path.getParent().getFileName().toString().equals("mods");
        return !inMods && path.toString().endsWith(".jar") && Files.isRegularFile(path) ? path : null;
    }
}
