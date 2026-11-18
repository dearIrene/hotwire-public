package dev.moon.hotwire.engine;

import dev.moon.hotwire.jar.JarPaths;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.plan.ModGraph;
import dev.moon.hotwire.registration.Owners;
import dev.moon.hotwire.update.PendingChange;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InstalledMods {
    private final Set<String> runningFromCopy = new HashSet<>();
    private final Map<String, String> copyOf = new HashMap<>();
    private static final Logger LOGGER = LoggerFactory.getLogger(InstalledMods.class);
    private ModGraph graph;
    private List<ModJar> lastJarsWith = List.of();
    private @Nullable ModGraph lastGraphWith;
    private final List<ModJar> jars;

    private final List<ModJar> nestedAdded = new ArrayList<>();

    private final List<ModJar> partsAdded = new ArrayList<>();
    private List<ModJar> partsOfLastAdd = List.of();

    private final Map<String, Path> jarOf = new HashMap<>();

    private final Map<String, Path> folderJarOf = new HashMap<>();
    private final Set<String> topLevel = new HashSet<>();

    private final Map<String, ModJar> disabled = new HashMap<>();
    private final Set<String> addedThisSession = new HashSet<>();

    private final Map<String, String> offeredFor = new HashMap<>();
    private final Set<String> updatedThisSession = new HashSet<>();

    public InstalledMods(List<ModJar> jars, Map<String, Path> jarOf, Set<String> topLevel,
            Map<String, ModJar> disabled) {
        this.jars = new ArrayList<>(jars);
        this.jarOf.putAll(jarOf);
        this.folderJarOf.putAll(jarOf);
        this.topLevel.addAll(topLevel);
        this.disabled.putAll(disabled);
        for (ModJar jar : jars) {
            for (String target : jar.patches().patches()) {
                if (target.startsWith("dev/moon/hotwire/")) {
                    LOGGER.warn("[Hotwire] {} patches Hotwire's own {}; what Hotwire does with it on is that mod's",
                            jar.manifest().id(), target);
                }
            }
        }
        this.graph = tell(new ModGraph(this.jars, this::isOfGame, LoadedMods::findVersion));
        this.graph.getIds().forEach(id -> {
            List<String> called = this.graph.getCalls(id).stream().filter(ModPolicy::isSwitchable)
                    .filter(this.graph::isInstalledOnItsOwn).toList();
            List<String> needed = called.stream().filter(other -> this.graph.needsByCode(id, other)).toList();
            if (!needed.isEmpty()) {
                LOGGER.info("[Hotwire] {}'s code calls {}, which its manifest does not name; it goes off with them",
                        id, needed);
            }
            if (needed.size() < called.size()) {
                LOGGER.info("[Hotwire] {} asks for {} before it calls them, a compatibility layer; it stays on when"
                        + " they go off", id, called.stream().filter(other -> !needed.contains(other)).toList());
            }
        });
    }

    private static ModGraph tell(ModGraph made) {
        Owners.useShared(made.findSharedNested());
        return made;
    }

    private final Map<String, Boolean> ofGame = new ConcurrentHashMap<>();

    private boolean isOfGame(String type) {
        return this.ofGame.computeIfAbsent(type, SystemClassPath::has);
    }

    public synchronized ModGraph getGraph() {
        return this.graph;
    }

    public synchronized List<ModJar> getGraphJars() {
        List<ModJar> all = new ArrayList<>(this.jars);
        all.addAll(this.nestedAdded);
        all.addAll(this.partsAdded);
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

    public synchronized @Nullable Path findFolderJarOf(String id) {
        return this.folderJarOf.get(id);
    }

    public synchronized boolean isRunningFromFolderJar(String id) {
        Path folder = this.folderJarOf.get(id);
        return folder == null || folder.equals(this.jarOf.get(id));
    }

    private void setFolderJar(String id, Path jar) {
        if (this.isRunningFromFolderJar(id)) {
            this.jarOf.put(id, jar);
        }
        this.folderJarOf.put(id, jar);
    }

    public synchronized boolean isTopLevel(String id) {
        return this.topLevel.contains(id);
    }

    public synchronized SortedSet<String> getTopLevel() {
        SortedSet<String> rows = new TreeSet<>(this.topLevel);
        rows.removeAll(this.runningFromCopy);
        return rows;
    }

    public synchronized void onRunningFromCopy(String id) {
        this.runningFromCopy.add(id);
    }

    public synchronized void onCopyOf(String copy, String of) {
        this.copyOf.put(copy, of);
    }

    public synchronized @Nullable String findCopyOf(String id) {
        return this.copyOf.get(id);
    }

    public synchronized Map<String, Path> getJarsByTopLevel() {
        Map<String, Path> out = new HashMap<>();
        for (String id : this.topLevel) {
            Path jar = this.jarOf.get(id);
            if (jar != null) {
                out.put(id, jar);
            }
        }
        return out;
    }

    public synchronized String nameOf(String id) {
        return this.graph.find(id).map(ModManifest::name).orElseGet(() -> this.findDisabled(id)
                .map(ModManifest::name).orElse(id));
    }

    public synchronized boolean isDisabled(String id) {
        return this.disabled.containsKey(id);
    }

    private final List<PendingChange.OffAtStart> offAtStart = new CopyOnWriteArrayList<>();

    public List<PendingChange.OffAtStart> getOffAtStart() {
        return this.offAtStart;
    }

    public boolean isGoneAtNextStart(String id) {
        return this.isDisabled(id) || this.offAtStart.stream().anyMatch(off -> off.ids().contains(id));
    }

    public synchronized Optional<ModManifest> findDisabled(String id) {
        return Optional.ofNullable(this.disabled.get(id)).map(ModJar::manifest);
    }

    public synchronized boolean isDisabledSinceStart(String id) {
        return this.disabled.containsKey(id)
                && this.jars.stream().noneMatch(jar -> jar.manifest().id().equals(id));
    }

    public synchronized void onDisabled(String id, Path renamed) {
        this.jars.stream().filter(jar -> jar.manifest().id().equals(id)).findFirst()
                .ifPresent(jar -> this.disabled.put(id, jar));
        this.setFolderJar(id, renamed);
    }

    public synchronized void onRestored(String id, Path jar) {
        this.disabled.remove(id);
        this.setFolderJar(id, jar);
    }

    private void makeGraphAgain() {
        List<ModJar> all;
        synchronized (this) {
            all = new ArrayList<>(this.jars);
            all.addAll(this.partsAdded);
        }
        ModGraph made = tell(new ModGraph(all, this::isOfGame, LoadedMods::findVersion));
        synchronized (this) {
            this.graph = made;
        }
    }

    public ModGraph makeGraphWith(Collection<ModJar> more) {
        Set<String> inside = new HashSet<>();
        more.forEach(jar -> addNestedIds(jar, inside));
        List<ModJar> all;
        synchronized (this) {
            all = new ArrayList<>(this.jars);
            this.partsAdded.stream().filter(part -> more.stream().noneMatch(jar -> jar.manifest().id()
                    .equals(part.manifest().id()))).forEach(all::add);
        }
        more.stream().filter(jar -> !inside.contains(jar.manifest().id())).forEach(all::add);
        synchronized (this) {
            ModGraph last = this.lastGraphWith;
            if (last != null && isSame(this.lastJarsWith, all)) {
                return last;
            }
        }
        ModGraph made = new ModGraph(all, this::isOfGame, LoadedMods::findVersion);
        synchronized (this) {
            this.lastJarsWith = all;
            this.lastGraphWith = made;
        }
        return made;
    }

    @SuppressWarnings("ReferenceEquality")
    private static boolean isSame(List<ModJar> one, List<ModJar> other) {
        if (one.size() != other.size()) {
            return false;
        }
        for (int i = 0; i < one.size(); i++) {
            if (one.get(i) != other.get(i) && !one.get(i).equals(other.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static void addNestedIds(ModJar jar, Set<String> ids) {
        for (ModJar nested : jar.nested()) {
            ids.add(nested.manifest().id());
            addNestedIds(nested, ids);
        }
    }

    public void onAdded(Path jar, ModJar read, Collection<String> ids) {
        this.onAddedTogether(Map.of(jar, read), ids);
    }

    public synchronized void rememberParts(List<ModJar> parts) {
        this.partsOfLastAdd = List.copyOf(parts);
    }

    public void onAddedTogether(Map<Path, ModJar> reads, Collection<String> ids) {
        synchronized (this) {
            reads.forEach((jar, read) -> {
                this.jars.add(read);
                for (ModJar nested : read.nested()) {
                    if (ids.contains(nested.manifest().id())) {
                        this.nestedAdded.add(nested);
                    }
                }
                this.topLevel.add(read.manifest().id());
                this.jarOf.put(read.manifest().id(), jar);
                this.folderJarOf.put(read.manifest().id(), jar);
            });
            this.addedThisSession.addAll(ids);
            Set<String> known = new HashSet<>();
            for (ModJar jar : this.jars) {
                known.add(jar.manifest().id());
                addNestedIds(jar, known);
            }
            this.partsAdded.forEach(part -> known.add(part.manifest().id()));
            this.partsOfLastAdd.stream().filter(part -> known.add(part.manifest().id()))
                    .forEach(this.partsAdded::add);
            this.partsOfLastAdd = List.of();
        }
        this.makeGraphAgain();
    }

    public synchronized void onMoved(String id, Path jar) {
        if (this.jarOf.containsKey(id)) {
            this.setFolderJar(id, jar);
        }
    }

    public void onUpdated(Map<String, Path> paths, Map<String, ModJar> reads) {
        synchronized (this) {
            this.jars.removeIf(old -> reads.containsKey(old.manifest().id()));
            this.jars.addAll(reads.values());
            this.jarOf.putAll(paths);
            this.updatedThisSession.addAll(reads.keySet());
        }
        this.makeGraphAgain();
    }

    public synchronized void noteOffered(Path jar, String id) {
        this.offeredFor.put(jar.getFileName().toString(), id);
    }

    public synchronized @Nullable String findOfferedFor(Path jar) {
        String id = this.offeredFor.get(jar.getFileName().toString());
        return id != null && this.graph.find(id).isPresent() ? id : null;
    }

    public synchronized boolean isAddedThisSession(String id) {
        return this.addedThisSession.contains(id);
    }

    public synchronized boolean isUpdatedThisSession(String id) {
        return this.updatedThisSession.contains(id);
    }

    public static @Nullable Path findLoadedFrom(ModContainer container, Path mods) {
        if (container.getOrigin().getKind() != ModOrigin.Kind.PATH || container.getOrigin().getPaths().size() != 1) {
            return null;
        }
        Path path = container.getOrigin().getPaths().getFirst().toAbsolutePath().normalize();
        Path parent = path.getParent();
        boolean inMods = parent != null && JarPaths.findKeysOf(parent).stream()
                .anyMatch(JarPaths.findKeysOf(mods)::contains);
        return !inMods && path.toString().endsWith(".jar") && Files.isRegularFile(path) ? path : null;
    }
}
