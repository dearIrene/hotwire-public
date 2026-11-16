package dev.moon.hotwire.fabric.update;

import dev.moon.hotwire.classfile.ClassNodes;
import dev.moon.hotwire.concurrent.Parallel;
import dev.moon.hotwire.config.Flags;
import dev.moon.hotwire.fabric.HotwireClient;
import dev.moon.hotwire.fabric.ModPolicy;
import dev.moon.hotwire.jar.JarLookup;
import dev.moon.hotwire.jar.JarNames;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.mixin_copy.MixinClassInfo;
import dev.moon.hotwire.redefine.RebuildException;
import dev.moon.hotwire.switching.SwitchTimes;
import dev.moon.hotwire.update.JarLinks;
import dev.moon.hotwire.update.JarRelocation;
import dev.moon.hotwire.update.OwnPatch;
import dev.moon.hotwire.update.PinnedClasses;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class UpdateCopies {
    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateCopies.class);
    private final ModUpdater.Engine engine;
    private final Set<String> refusedCopies = ConcurrentHashMap.newKeySet();
    private final Map<Path, Map<String, String>> carriedGoing = new ConcurrentHashMap<>();
    private final Map<Path, Set<String>> carriedWithState = new ConcurrentHashMap<>();

    private final Map<String, Set<String>> patchersOf = new ConcurrentHashMap<>();

    private volatile Set<String> unchanged = Set.of();

    private final Set<String> unchangedClasses = ConcurrentHashMap.newKeySet();

    private final Set<String> ownPatched = ConcurrentHashMap.newKeySet();

    private @Nullable List<OwnPatch> ownPatches;

    private volatile Set<String> aimed = Set.of();
    private volatile Set<String> leaving = Set.of();
    private volatile Map<String, String> picked = Map.of();

    private static final int MOST_IN_A_COPY_GROUP = Flags.COPY_GROUP_CAP;
    private static final int MOST_ROUNDS = 6;

    private static final Pattern SLASHED_NAME = Pattern.compile("[a-zA-Z0-9_$]+(?:/[a-zA-Z0-9_$]+)+");

    void forgetBatch() {
        this.refusedCopies.clear();
        this.carriedGoing.clear();
        this.carriedWithState.clear();
        this.patchersOf.clear();
        this.ownPatched.clear();
        this.chooseFor(Set.of(), Map.of());
    }

    private void chooseFor(Set<String> group, Map<String, Path> given) {
        if (group.equals(this.aimed)) {
            return;
        }
        this.aimed = Set.copyOf(group);
        this.leaving = Set.of();
        this.picked = Map.of();
        this.carriedGoing.clear();
        this.carriedWithState.clear();
        if (group.isEmpty()) {
            return;
        }
        List<Path> jars = group.stream().map(id -> this.findJarOf(id, given)).filter(Objects::nonNull).distinct()
                .toList();
        try {
            this.picked = JarRelocation.findPicked(jars, ModUpdater.VERSION_ORDER);
        } catch (IOException | RuntimeException e) {
            LOGGER.info("[Hotwire] {}'s jars could not be read through for what they ship: {}", group, e.toString());
            return;
        }
        for (int round = 0; round < MOST_ROUNDS; round++) {
            Set<String> offs = this.findOffs(group, given);
            if (offs.equals(this.leaving)) {
                break;
            }
            this.leaving = offs;
            this.carriedGoing.clear();
            this.carriedWithState.clear();
        }
    }

    UpdateCopies(ModUpdater.Engine engine) {
        this.engine = engine;
    }

    List<Set<String>> findRestartGroups(Collection<String> waiting, Map<String, Path> given,
            Function<String, List<String>> leavingWith) {
        this.patchersOf.clear();
        this.ownPatched.clear();
        this.chooseFor(Set.of(), Map.of());
        this.unchanged = this.findUnchanged(waiting, given);
        SwitchTimes.mark("copies: unchanged");
        return this.runWithUnchanged(() -> this.findGroups(waiting, given, leavingWith));
    }

    private Set<String> findUsersOf(Set<String> group) {
        Set<String> leaving = this.findLeavingClasses(group);
        Set<String> users = new ConcurrentSkipListSet<>();

        Parallel.runEachBeside(List.copyOf(this.engine.on()), id -> {
            Path jar = group.contains(id) || this.unchanged.contains(id) || ModPolicy.isHotwire(id) ? null
                    : this.engine.findRunningJarOf(id);
            try {
                if (jar != null && !JarNames.get().findNamedOf(jar, leaving, false).isEmpty()) {
                    users.add(id);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.info("[Hotwire] {} could not be read through: {}", id, e.toString());
            }
        });
        JarNames.get().flush();
        if (!users.isEmpty()) {
            LOGGER.info("[Hotwire] {} use classes of {} mods that go, and go with them", users, group.size());
        }
        return users;
    }

    PinnedClasses findPinned(Set<String> group, Set<String> offs) {
        Set<String> leaving = this.findLeavingClasses(offs);
        Set<Path> jars = new HashSet<>();
        for (String id : group) {
            for (String needed : this.engine.getGraph().getUses(id)) {
                Path jar = offs.contains(needed) || group.contains(needed) ? null
                        : this.engine.findRunningJarOf(needed);
                if (jar != null) {
                    jars.add(jar);
                }
            }
        }
        Set<String> named = new ConcurrentSkipListSet<>();
        Parallel.runEachBeside(jars, jar -> {
            try {
                named.addAll(JarNames.get().findNamedOf(jar, leaving, true));
            } catch (IOException | RuntimeException e) {
                LOGGER.info("[Hotwire] {} could not be read through: {}", jar.getFileName(), e.toString());
            }
        });
        if (!named.isEmpty()) {
            LOGGER.info("[Hotwire] {} keep their names in the copies: what the mods need calls them by name", named);
        }
        return new PinnedClasses(named);
    }

    void putPinned(Map<String, byte[]> bodies) {
        if (bodies.isEmpty()) {
            return;
        }
        try {
            this.engine.getApplier().replace(List.of(), bodies, this.engine.on(), Set.of(), () -> { });
        } catch (RebuildException | RuntimeException | LinkageError e) {
            LOGGER.error("[Hotwire] {} could not take their bodies in place", bodies.keySet(), e);
        }
    }

    Set<String> findLeavingClasses(Set<String> offs) {
        Set<String> classes = new HashSet<>();

        Predicate<String> goes = nested -> offs.contains(this.engine.getGraph().resolve(nested));
        for (String id : offs) {
            Path jar = this.engine.findRunningJarOf(id);
            try {
                if (jar != null) {
                    classes.addAll(JarRelocation.readClassesOf(jar, goes));
                }
            } catch (IOException e) {
                LOGGER.info("[Hotwire] {} could not be read through: {}", jar.getFileName(), e.toString());
            }
        }
        classes.removeAll(this.unchangedClasses);
        return classes;
    }

    <T> T runWithUnchanged(Supplier<T> work) {
        ModPolicy.setStayingThrough(this.unchanged);
        try {
            return work.get();
        } finally {
            ModPolicy.setStayingThrough(Set.of());
        }
    }

    private Set<String> findUnchanged(Collection<String> waiting, Map<String, Path> given) {
        List<Path> jars = waiting.stream().map(given::get).filter(Objects::nonNull).distinct().toList();
        Set<String> same = new TreeSet<>();
        this.unchangedClasses.clear();
        try {
            Map<String, Map<String, byte[]>> found = new HashMap<>();
            Set<String> changingMods = new HashSet<>(waiting);
            long[] took = new long[3];
            long began = System.nanoTime();
            Map<String, Map<String, byte[]>> picked = JarRelocation.readPicked(jars, ModUpdater.VERSION_ORDER);
            took[0] = System.nanoTime() - began;
            picked.forEach((id, files) -> {
                changingMods.add(id);
                if (ModPolicy.isSwitchable(id) && this.engine.findVersionOf(id) != null) {
                    long from = System.nanoTime();
                    Map<String, byte[]> running = this.engine.readRunningOf(id);
                    took[1] += System.nanoTime() - from;
                    from = System.nanoTime();
                    if (hasSameFiles(files, running)) {
                        found.put(id, files);
                    }
                    took[2] += System.nanoTime() - from;
                }
            });
            LOGGER.info("[Hotwire] {} libraries of the new jars were compared with what runs: the jars read in {} ms,"
                    + " what runs in {} ms, compared in {} ms", picked.size(), took[0] / 1_000_000,
                    took[1] / 1_000_000, took[2] / 1_000_000);
            SwitchTimes.mark("unchanged: files");
            Set<String> changing = new HashSet<>();
            for (Path jar : jars) {
                changing.addAll(JarLinks.readClassNames(jar));
            }
            SwitchTimes.mark("unchanged: names");
            found.values().forEach(files -> files.keySet().stream().filter(name -> name.endsWith(".class"))
                    .forEach(name -> changing.remove(name.substring(0, name.length() - 6))));
            changingMods.removeAll(found.keySet());
            for (String id : JarRelocation.findApart(found, changing, changingMods)) {
                same.add(id);
                Objects.requireNonNull(found.get(id)).keySet().stream().filter(name -> name.endsWith(".class"))
                        .forEach(name -> this.unchangedClasses.add(name.substring(0, name.length() - 6)));
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.info("[Hotwire] the new jars could not be read through for what they ship unchanged: {}",
                    e.toString());
            return Set.of();
        }
        if (!same.isEmpty()) {
            LOGGER.info("[Hotwire] {} are in the new jars just as they run, and stay on through the update", same);
        }
        return same;
    }

    private static boolean hasSameFiles(Map<String, byte[]> shipped, Map<String, byte[]> running) {
        Map<String, byte[]> files = new HashMap<>(shipped);
        files.keySet().removeIf(name -> name.endsWith("/"));
        if (running.isEmpty() || !files.keySet().equals(running.keySet())) {
            return false;
        }
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            if (!Arrays.equals(file.getValue(), running.get(file.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private List<Set<String>> findGroups(Collection<String> waiting, Map<String, Path> given,
            Function<String, List<String>> leavingWith) {
        List<Set<String>> groups = new ArrayList<>();
        for (String id : waiting) {
            if (!ModPolicy.isSwitchable(id) || groups.stream().anyMatch(group -> group.contains(id))) {
                continue;
            }
            Set<String> group = new LinkedHashSet<>();
            Deque<String> open = new ArrayDeque<>(List.of(id));
            Set<String> asked = new HashSet<>();
            while (!open.isEmpty()) {
                String at = open.remove();

                if (!this.engine.getApplier().patchesLiveBackend(at) && !this.unchanged.contains(at)
                        && group.add(at)) {
                    open.addAll(this.engine.getGraph().getDependents(at));
                    this.findCarriedWithState(at, given)
                            .forEach(carried -> open.addAll(this.engine.getGraph().getDependents(carried)));

                    this.findCarriedGoing(at, given).keySet().stream()
                            .filter(carried -> this.engine.findVersionOf(carried) != null
                                    && !this.unchanged.contains(carried)
                                    && this.engine.getGraph().getContainers(carried).contains(at))
                            .forEach(carried -> open.addAll(this.engine.getGraph().getDependents(carried)));
                    open.addAll(this.findPatchersOf(at, given));
                    open.addAll(this.findWaitingNeeded(at, waiting, given));
                    open.addAll(leavingWith.apply(at));
                }

                if (open.isEmpty() && !this.isSureToWait(group, given)) {
                    this.findUsersOf(group).stream().filter(asked::add).forEach(open::add);
                }
            }
            if (group.isEmpty()) {
                continue;
            }
            groups.removeIf(other -> {
                boolean meets = !Collections.disjoint(other, group);
                if (meets) {
                    group.addAll(other);
                }
                return meets;
            });
            groups.add(group);
        }
        return groups;
    }

    private boolean isSureToWait(Set<String> group, Map<String, Path> given) {
        return (this.isOverCap(group) && !UpdateSupport.ASKS_OVER_CAP) || group.stream()
                .anyMatch(id -> !ModPolicy.isSwitchable(id) && this.findJarOf(id, given) != null);
    }

    @Nullable String findRestartRefusal(Set<String> group, Map<String, Path> given) {
        if (this.isOverCap(group)) {
            return "with " + group.size() + " mods going together, it takes a restart";
        }
        return this.findRefusalUnderCap(group, given);
    }

    boolean isOverCap(Set<String> group) {
        return group.size() > MOST_IN_A_COPY_GROUP;
    }

    @Nullable String findRefusalUnderCap(Set<String> group, Map<String, Path> given) {
        this.chooseFor(group, given);
        Map<String, ModManifest> manifests = new LinkedHashMap<>();
        Map<String, String> coming = new HashMap<>();
        for (String id : group) {
            Path jar = this.findJarOf(id, given);

            if (jar == null) {
                continue;
            }
            ModManifest manifest = JarLookup.findManifest(jar);
            if (manifest == null || this.engine.findRunningJarOf(id) == null) {
                return id + " is not a mod the game runs";
            }
            if (!ModPolicy.isSwitchable(id)) {
                return id + " stays on";
            }
            if (!manifest.id().equals(id)) {
                return id + " changed its id to " + manifest.id();
            }
            if (this.refusedCopies.contains(id)) {
                return id + "'s patches reach what the game already made";
            }
            manifests.put(id, manifest);
            coming.put(id, manifest.version());
            manifest.provides().forEach(provided -> coming.put(provided, manifest.version()));
            coming.putAll(this.findCarriedGoing(id, given));
        }
        for (Map.Entry<String, ModManifest> entry : manifests.entrySet()) {
            String id = entry.getKey();
            for (Map.Entry<String, List<String>> wants : entry.getValue().getRelation(ModManifest.Relation.DEPENDS)
                    .entrySet()) {
                String version = coming.getOrDefault(wants.getKey(), this.engine.findVersionOf(wants.getKey()));
                if (version != null && !this.engine.satisfies(version, wants.getValue())) {
                    return id + " needs " + wants.getKey() + " " + String.join(" or ", wants.getValue())
                            + ", and it would find " + version;
                }
            }
            String runs = this.engine.findVersionOf(id);
            if (runs != null && ModUpdater.VERSION_ORDER.compare(entry.getValue().version(), runs) < 0) {
                return id + " " + entry.getValue().version() + " is older than what runs";
            }
            Set<String> patching = new TreeSet<>(this.findPatchersOf(id, given));
            patching.removeAll(group);
            if (!patching.isEmpty()) {
                return patching + " patch classes of " + id;
            }

            if (this.ownPatched.contains(id)) {
                return "Hotwire's own patches of " + id + " would not follow its copy";
            }
            String shut = this.findInterfacePatched(id, given);
            if (shut != null) {
                return id + " patches the loaded interface " + shut;
            }
        }
        return null;
    }

    List<Path> findRestartJars(Set<String> group, Map<String, Path> given) {
        this.chooseFor(group, given);
        Set<String> carried = new HashSet<>();
        group.forEach(id -> carried.addAll(this.findCarriedGoing(id, given).keySet()));
        List<String> order = new ArrayList<>();
        Set<String> left = new LinkedHashSet<>(group);
        while (!left.isEmpty()) {
            String next = left.stream().filter(id -> left.stream().noneMatch(other -> !other.equals(id)
                    && this.engine.getGraph().getDependents(other).contains(id)))
                    .findFirst().orElse(left.iterator().next());
            left.remove(next);
            order.add(next);
        }
        List<Path> jars = new ArrayList<>();
        for (String id : order) {
            Path jar = this.findJarOf(id, given);
            if (jar != null && !carried.contains(id)) {
                jars.add(jar);
            }
        }
        return jars;
    }

    Set<String> findRestartOffs(Set<String> group, Map<String, Path> given) {
        this.chooseFor(group, given);
        return this.findOffs(group, given);
    }

    private Set<String> findOffs(Set<String> group, Map<String, Path> given) {
        Set<String> carried = new HashSet<>();
        group.forEach(id -> carried.addAll(this.findCarriedGoing(id, given).keySet()));
        Set<String> offs = new LinkedHashSet<>(group);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (String id : carried) {
                if (!offs.contains(id) && this.engine.findVersionOf(id) != null && ModPolicy.isSwitchable(id)
                        && !this.unchanged.contains(id)
                        && offs.containsAll(this.engine.getGraph().getDependents(id))
                        && offs.containsAll(this.engine.getGraph().getContainers(id))) {
                    offs.add(id);
                    grew = true;
                }
            }
        }
        for (String id : carried) {
            if (!offs.contains(id) && this.engine.findVersionOf(id) != null && ModPolicy.isSwitchable(id)
                    && !this.unchanged.contains(id)) {
                Set<String> holding = new TreeSet<>(this.engine.getGraph().getDependents(id));
                holding.addAll(this.engine.getGraph().getContainers(id));
                holding.removeAll(offs);
                LOGGER.info("[Hotwire] {} stays on beside its copy: {} hold it", id, holding);
            }
        }
        return offs;
    }

    boolean takesCarried(String id, String version) {
        if (!ModPolicy.isSwitchable(id)) {
            return false;
        }
        String runs = this.engine.findVersionOf(id);
        if (runs != null && this.leaving.contains(id)) {
            return version.equals(this.picked.get(id));
        }
        return runs == null || !this.engine.satisfies(runs, List.of(">=" + version));
    }

    boolean patchesNamed(Set<String> group, String refusal) {
        Set<String> named = new HashSet<>();
        Matcher slashed = SLASHED_NAME.matcher(refusal);
        while (slashed.find()) {
            named.add(slashed.group());
        }
        Set<String> patching = new HashSet<>();
        this.engine.getApplier().getPatchersOf(named, this.engine.on(),
                "").values().forEach(patching::addAll);
        return !Collections.disjoint(patching, group);
    }

    void refuseCopies(String id) {
        this.refusedCopies.add(id);
    }

    @Nullable Path findJarOf(String id, Map<String, Path> given) {
        Path jar = given.get(id);
        return jar != null ? jar : this.engine.findRunningJarOf(id);
    }

    Set<String> findCarriedWithState(String id, Map<String, Path> given) {
        Path jar = this.findJarOf(id, given);
        if (jar == null) {
            return Set.of();
        }
        return this.carriedWithState.computeIfAbsent(jar, one -> {
            try {
                return JarRelocation.findCarriedWithState(one, this::takesCarried);
            } catch (IOException | RuntimeException e) {
                LOGGER.info("[Hotwire] {} could not be read through: {}", one.getFileName(), e.toString());
                return Set.of();
            }
        });
    }

    @Nullable String findInterfacePatched(String id, Map<String, Path> given) {
        Path jar = given.get(id);
        if (jar == null) {
            return null;
        }
        try {
            for (byte[] bytes : JarRelocation.readMixinClasses(jar, this::takesCarried)) {
                Set<String> targets = MixinClassInfo.read(bytes).targets();
                if (!targets.isEmpty() && hasDefaultMethod(bytes)) {
                    for (String target : targets) {
                        Class<?> loaded = this.engine.getClasses().findLoaded(target);
                        if (loaded != null && loaded.isInterface()) {
                            return target;
                        }
                    }
                }
            }
            return null;
        } catch (IOException e) {
            return String.valueOf(e);
        }
    }

    static boolean hasDefaultMethod(byte[] mixin) {
        ClassNode node = ClassNodes.read(mixin,
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (MethodNode method : node.methods) {
            boolean shut = (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0;
            if (!shut && !method.name.startsWith("<")
                    && (method.visibleAnnotations == null || method.visibleAnnotations.isEmpty())
                    && (method.invisibleAnnotations == null || method.invisibleAnnotations.isEmpty())) {
                return true;
            }
        }
        return false;
    }

    Set<String> findWaitingNeeded(String id, Collection<String> waiting, Map<String, Path> given) {
        Path jar = given.get(id);
        ModManifest manifest = jar == null ? null : JarLookup.findManifest(jar);
        if (manifest == null) {
            return Set.of();
        }
        Set<String> needed = new TreeSet<>();
        manifest.getRelation(ModManifest.Relation.DEPENDS).forEach((mod, ranges) -> {
            String runs = this.engine.findVersionOf(mod);
            if (runs == null || !this.engine.satisfies(runs, ranges)) {
                for (String other : waiting) {
                    if (other.equals(this.engine.getGraph().resolve(mod))
                            || this.findCarriedGoing(other, given).containsKey(mod)) {
                        needed.add(other);
                    }
                }
            }
        });
        return needed;
    }

    synchronized List<OwnPatch> getOwnPatches() {
        if (this.ownPatches == null) {
            try {
                this.ownPatches = OwnPatch.readAll(UpdateCopies.class.getClassLoader(),
                        HotwireClient.ID + ".mixins.json");
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("[Hotwire] Hotwire's own mixins could not be read", e);
                this.ownPatches = List.of();
            }
        }
        return this.ownPatches;
    }

    Set<String> findPatchersOf(String id, Map<String, Path> given) {
        Path jar = this.findJarOf(id, given);
        if (jar == null) {
            return Set.of();
        }
        return this.patchersOf.computeIfAbsent(id, any -> {
            try {
                Set<String> patching = new TreeSet<>();
                Set<String> classes = JarLinks.readClassNames(jar);
                classes.removeAll(this.unchangedClasses);
                this.engine.getApplier().getPatchersOf(classes, this.engine.on(), id).values()
                        .forEach(patching::addAll);
                if (this.getOwnPatches().stream().anyMatch(patch -> !patch.canFollow()
                        && !Collections.disjoint(patch.getTargets(), classes))) {
                    this.ownPatched.add(id);
                }

                patching.removeIf(mod -> !ModPolicy.isSwitchable(mod));
                return patching;
            } catch (IOException e) {
                LOGGER.info("[Hotwire] {} could not be read through: {}", jar.getFileName(), e.toString());
                return Set.of();
            }
        });
    }

    Map<String, String> findCarriedGoing(String id, Map<String, Path> given) {
        Path jar = this.findJarOf(id, given);
        if (jar == null) {
            return Map.of();
        }
        return this.carriedGoing.computeIfAbsent(jar, one -> {
            try {
                return JarRelocation.findCarried(one, this::takesCarried);
            } catch (IOException | RuntimeException e) {
                LOGGER.info("[Hotwire] {} could not be read through: {}", one.getFileName(), e.toString());
                return Map.of();
            }
        });
    }
}
