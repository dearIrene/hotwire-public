package dev.moon.hotwire.plan;

import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.jar.ModManifest.Relation;
import dev.moon.hotwire.jar.ModPatches;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

public final class ModGraph {
    private static final int GAME_TRIES = 16;

    private final Map<String, ModManifest> mods = new TreeMap<>();
    private final Map<String, SortedSet<String>> getContainers = new TreeMap<>();
    private final Set<String> installed = new TreeSet<>();
    private final Map<String, String> aliases = new HashMap<>();
    private final Map<String, SortedSet<String>> dependents = new TreeMap<>();
    private final Map<String, ModPatches> patches = new HashMap<>();
    private final Map<String, SortedSet<String>> hardPatchersByClass = new HashMap<>();

    private final Map<String, SortedSet<String>> calls = new TreeMap<>();

    private final Map<String, SortedSet<String>> uses = new ConcurrentHashMap<>();

    private final Predicate<String> gameHas;

    private final Function<String, @Nullable String> running;

    public ModGraph(Collection<ModJar> jars) {
        this(jars, type -> false, id -> null);
    }

    public ModGraph(Collection<ModJar> jars, Predicate<String> gameHas, Function<String, @Nullable String> running) {
        this.gameHas = gameHas;
        this.running = running;
        for (ModJar jar : jars) {
            this.add(jar, Optional.empty());
            this.installed.add(jar.manifest().id());
        }
        for (ModManifest mod : this.mods.values()) {
            mod.provides().forEach(alias -> this.aliases.putIfAbsent(alias, mod.id()));
        }
        for (ModManifest mod : this.mods.values()) {
            for (String dependency : mod.getRelation(Relation.DEPENDS).keySet()) {
                this.dependents.computeIfAbsent(this.resolve(dependency), key -> new TreeSet<>()).add(mod.id());
            }
        }
        this.patches.forEach((mod, patched) -> patched.hardPatches().forEach(type ->
                this.hardPatchersByClass.computeIfAbsent(type, key -> new TreeSet<>()).add(mod)));
        this.addCalls();
    }

    private void addCalls() {
        Map<String, String> ownerOf = new HashMap<>();
        Set<String> shared = new TreeSet<>();
        Map<String, Integer> tries = new HashMap<>();
        this.patches.forEach((mod, patched) -> patched.owns().forEach(type -> {
            int slash = type.lastIndexOf('/');
            String in = slash < 0 ? "" : type.substring(0, slash);
            String before = ownerOf.putIfAbsent(in, mod);

            if ((before != null && !before.equals(mod)) || (!shared.contains(in)
                    && tries.merge(in, 1, Integer::sum) <= GAME_TRIES && this.gameHas.test(type))) {
                shared.add(in);
            }
        }));
        shared.forEach(ownerOf::remove);

        Map<String, List<ModManifest>> askable = new HashMap<>();
        for (ModManifest mod : this.mods.values()) {
            askable.computeIfAbsent(mod.id(), key -> new ArrayList<>()).add(mod);
            mod.provides().forEach(alias -> askable.computeIfAbsent(alias, key -> new ArrayList<>()).add(mod));
        }
        this.patches.forEach((mod, patched) -> {
            ModManifest manifest = this.mods.get(mod);
            for (String in : patched.calls()) {
                String owner = ownerOf.get(in);
                if (manifest != null && owner != null && !owner.equals(mod) && !this.names(manifest, owner)
                        && !this.getContainers(mod).contains(owner) && !this.getContainers(owner).contains(mod)) {
                    this.calls.computeIfAbsent(mod, key -> new TreeSet<>()).add(owner);

                    if (!this.hasCompatibilityFor(mod, patched, owner, askable)) {
                        this.dependents.computeIfAbsent(owner, key -> new TreeSet<>()).add(mod);
                    }
                }
            }
        });
    }

    private boolean hasCompatibilityFor(String asker, ModPatches patched, String other,
            Map<String, List<ModManifest>> askable) {
        if (this.isAskedFor(patched, other)) {
            return true;
        }
        for (String asked : patched.asks()) {
            if (this.getContainers(asker).contains(asked)) {
                continue;
            }
            for (ModManifest mod : askable.getOrDefault(asked, List.of())) {
                if (mod.getRelation(Relation.DEPENDS).keySet().stream()
                        .anyMatch(dependency -> this.resolve(dependency).equals(other))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isAskedFor(ModPatches patched, String other) {
        ModManifest manifest = this.mods.get(other);
        return patched.asks().contains(other)
                || (manifest != null && manifest.provides().stream().anyMatch(patched.asks()::contains));
    }

    public boolean needsByCode(String mod, String other) {
        return this.getCalls(mod).contains(other) && this.dependents.getOrDefault(other, new TreeSet<>()).contains(mod);
    }

    private boolean names(ModManifest manifest, String other) {
        for (Relation relation : Relation.values()) {
            if (manifest.getRelation(relation).keySet().stream().anyMatch(id -> this.resolve(id).equals(other))) {
                return true;
            }
        }
        return false;
    }

    public SortedSet<String> getCalls(String id) {
        return new TreeSet<>(this.calls.getOrDefault(id, new TreeSet<>()));
    }

    private void add(ModJar jar, Optional<String> container) {
        String id = jar.manifest().id();
        ModManifest known = this.mods.get(id);
        String runs = this.running.apply(id);
        if (known == null || (runs != null && !runs.equals(known.version()) && runs.equals(jar.manifest().version()))) {
            this.mods.put(id, jar.manifest());
            this.patches.put(id, jar.patches());
        }
        container.ifPresent(parent -> this.getContainers.computeIfAbsent(id, key -> new TreeSet<>()).add(parent));
        jar.nested().forEach(nested -> this.add(nested, Optional.of(id)));
    }

    public Optional<ModManifest> find(String id) {
        return Optional.ofNullable(this.mods.get(this.resolve(id)));
    }

    public Set<String> getIds() {
        return this.mods.keySet();
    }

    public String resolve(String idOrAlias) {
        return this.mods.containsKey(idOrAlias) ? idOrAlias : this.aliases.getOrDefault(idOrAlias, idOrAlias);
    }

    public SortedSet<String> findPatchersOf(Set<String> classes) {
        SortedSet<String> out = new TreeSet<>();
        this.patches.forEach((id, patched) -> {
            if (!Collections.disjoint(patched.patches(), classes)) {
                out.add(id);
            }
        });
        return out;
    }

    public SortedSet<String> getDependents(String id) {
        SortedSet<String> out = new TreeSet<>(this.dependents.getOrDefault(id, new TreeSet<>()));
        out.remove(id);
        return out;
    }

    List<String> getDependencies(String id) {
        List<String> out = new ArrayList<>(this.find(id).map(mod -> mod.getRelation(Relation.DEPENDS).keySet().stream()
                .map(this::resolve).toList()).orElse(List.of()));

        this.calls.getOrDefault(id, new TreeSet<>()).stream().filter(other -> this.needsByCode(id, other))
                .forEach(out::add);
        return out;
    }

    public SortedSet<String> getUses(String id) {
        return this.uses.computeIfAbsent(id, one -> Collections.unmodifiableSortedSet(this.findUses(one)));
    }

    private SortedSet<String> findUses(String id) {
        SortedSet<String> used = new TreeSet<>();
        Deque<String> next = new ArrayDeque<>(List.of(id));
        while (!next.isEmpty()) {
            String one = next.poll();
            List<String> direct = new ArrayList<>(this.getDependencies(one));
            direct.addAll(this.getChildrenOf(one));
            for (String other : direct) {
                if (!other.equals(id) && used.add(other)) {
                    next.add(other);
                }
            }
        }
        return used;
    }

    public SortedSet<String> getContainers(String id) {
        return this.getContainers.getOrDefault(id, new TreeSet<>());
    }

    public boolean isInstalledOnItsOwn(String id) {
        return this.installed.contains(id);
    }

    public SortedSet<String> findSharedNested() {
        SortedSet<String> shared = new TreeSet<>();
        for (String id : this.getContainers.keySet()) {
            if (!this.installed.contains(id) && this.findTopContainers(id).size() > 1) {
                shared.add(id);
            }
        }
        return shared;
    }

    private Set<String> findTopContainers(String id) {
        Set<String> tops = new TreeSet<>();
        Set<String> seen = new HashSet<>();
        Deque<String> next = new ArrayDeque<>(this.getContainers(id));
        while (!next.isEmpty()) {
            String one = next.poll();
            if (seen.add(one)) {
                if (this.installed.contains(one)) {
                    tops.add(one);
                }
                next.addAll(this.getContainers(one));
            }
        }
        return tops;
    }

    SortedSet<String> getChildrenOf(String container) {
        SortedSet<String> out = new TreeSet<>();
        this.getContainers.forEach((child, carriedBy) -> {
            if (carriedBy.contains(container)) {
                out.add(child);
            }
        });
        return out;
    }

    Map<String, String> getPatchersOf(String owner) {
        Map<String, String> out = new TreeMap<>();
        for (String type : this.patches.getOrDefault(owner, ModPatches.NONE).owns()) {
            for (String patcher : this.hardPatchersByClass.getOrDefault(type, Collections.emptySortedSet())) {
                if (!patcher.equals(owner)) {
                    out.merge(patcher, type, (remaining, other) -> remaining.compareTo(other) <= 0 ? remaining : other);
                }
            }
        }
        return out;
    }
}
