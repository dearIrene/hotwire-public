package dev.moon.hotwire.fabric.update;

import dev.moon.hotwire.classfile.ClassNodes;
import dev.moon.hotwire.fabric.ModPolicy;
import dev.moon.hotwire.jar.JarLookup;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.mixin_copy.MixinClassInfo;
import dev.moon.hotwire.update.JarLinks;
import dev.moon.hotwire.update.JarRelocation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
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
    private static final int MOST_IN_A_COPY_GROUP = 12;

    private static final Pattern SLASHED_NAME = Pattern.compile("[a-zA-Z0-9_$]+(?:/[a-zA-Z0-9_$]+)+");

    void forgetBatch() {
        this.refusedCopies.clear();
        this.carriedGoing.clear();
        this.carriedWithState.clear();
        this.patchersOf.clear();
    }

    UpdateCopies(ModUpdater.Engine engine) {
        this.engine = engine;
    }

    List<Set<String>> findRestartGroups(Collection<String> waiting, Map<String, Path> given,
            Function<String, List<String>> leavingWith) {
        this.patchersOf.clear();
        List<Set<String>> groups = new ArrayList<>();
        for (String id : waiting) {
            if (!ModPolicy.isSwitchable(id) || groups.stream().anyMatch(group -> group.contains(id))) {
                continue;
            }
            Set<String> group = new LinkedHashSet<>();
            Deque<String> open = new ArrayDeque<>(List.of(id));
            while (!open.isEmpty()) {
                String at = open.remove();

                if (!this.engine.getApplier().patchesLiveBackend(at) && group.add(at)) {
                    open.addAll(this.engine.getGraph().getDependents(at));
                    this.findCarriedWithState(at, given)
                            .forEach(carried -> open.addAll(this.engine.getGraph().getDependents(carried)));
                    open.addAll(this.findPatchersOf(at, given));
                    open.addAll(this.findWaitingNeeded(at, waiting, given));
                    open.addAll(leavingWith.apply(at));
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
            String shut = this.findInterfacePatched(id, given);
            if (shut != null) {
                return id + " patches the loaded interface " + shut;
            }
        }
        return null;
    }

    List<Path> findRestartJars(Set<String> group, Map<String, Path> given) {
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
        Set<String> carried = new HashSet<>();
        group.forEach(id -> carried.addAll(this.findCarriedGoing(id, given).keySet()));
        Set<String> offs = new LinkedHashSet<>(group);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (String id : carried) {
                if (!offs.contains(id) && this.engine.findVersionOf(id) != null && ModPolicy.isSwitchable(id)
                        && offs.containsAll(this.engine.getGraph().getDependents(id))
                        && offs.containsAll(this.engine.getGraph().getContainers(id))) {
                    offs.add(id);
                    grew = true;
                }
            }
        }
        return offs;
    }

    boolean takesCarried(String id, String version) {
        if (!ModPolicy.isSwitchable(id)) {
            return false;
        }
        String runs = this.engine.findVersionOf(id);
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

    Set<String> findPatchersOf(String id, Map<String, Path> given) {
        Path jar = this.findJarOf(id, given);
        if (jar == null) {
            return Set.of();
        }
        return this.patchersOf.computeIfAbsent(id, any -> {
            try {
                Set<String> patching = new TreeSet<>();
                this.engine.getApplier().getPatchersOf(JarLinks.readClassNames(jar), this.engine.on(),
                        id).values()
                        .forEach(patching::addAll);

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
