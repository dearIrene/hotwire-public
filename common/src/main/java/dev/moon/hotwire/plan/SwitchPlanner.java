package dev.moon.hotwire.plan;

import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.jar.ModManifest.Relation;
import dev.moon.hotwire.plan.SwitchPlan.Direction;
import dev.moon.hotwire.plan.SwitchPlan.EnabledNestedMod;
import dev.moon.hotwire.plan.SwitchPlan.Reason;
import dev.moon.hotwire.plan.SwitchPlan.Refusal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

public final class SwitchPlanner {
    private final ModGraph graph;
    private final Settings settings;

    public SwitchPlanner(ModGraph graph, Settings settings) {
        this.graph = graph;
        this.settings = settings;
    }

    public enum NestedJars { AUTO, ALL, NONE }

    public interface Versions {
        Versions ANY = (version, ranges) -> true;

        boolean matches(String version, List<String> ranges);
    }

    public record Settings(NestedJars nestedJars, Set<String> builtIn, Set<String> protectedMods, Versions versions,
            KnownUses uses) {
        public Settings {
            builtIn = Set.copyOf(builtIn);
            protectedMods = Set.copyOf(protectedMods);
        }

        public Settings(NestedJars nestedJars, Set<String> builtIn, Set<String> protectedMods, Versions versions) {
            this(nestedJars, builtIn, protectedMods, versions, KnownUses.NONE);
        }

        boolean isProtected(String mod) {
            return this.builtIn.contains(mod) || this.protectedMods.contains(mod);
        }
    }

    public SwitchPlan planOff(Collection<String> asked, Set<String> alreadyOff) {
        List<Refusal> refusals = new ArrayList<>();
        Map<String, Reason> leaving = new LinkedHashMap<>();
        for (String mod : new TreeSet<>(asked)) {
            String id = this.graph.resolve(mod);
            if (this.graph.find(id).isEmpty()) {
                refusals.add(new Refusal.NotInstalled(mod));
            } else if (!alreadyOff.contains(id)) {
                leaving.put(id, new Reason.Asked());
            }
        }
        List<EnabledNestedMod> retained = new ArrayList<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            retained.clear();
            for (String leavingMod : new ArrayList<>(leaving.keySet())) {
                for (String dependent : this.graph.getDependents(leavingMod)) {
                    grew |= this.join(leaving, alreadyOff, dependent, new Reason.DependsOn(leavingMod));
                }

                for (String user : this.settings.uses().findUsersOf(leavingMod)) {
                    if (this.graph.find(user).isPresent()) {
                        grew |= this.join(leaving, alreadyOff, user, new Reason.DependsOn(leavingMod));
                    }
                }

                for (String container : this.graph.getContainers(leavingMod)) {
                    grew |= this.join(leaving, alreadyOff, container, new Reason.Ships(leavingMod));
                }
                for (Map.Entry<String, String> patcher : this.graph.getPatchersOf(leavingMod).entrySet()) {
                    grew |= this.join(leaving, alreadyOff, patcher.getKey(),
                            new Reason.PatchesClassOf(leavingMod, patcher.getValue()));
                }
                for (String child : this.graph.getChildrenOf(leavingMod)) {
                    if (alreadyOff.contains(child) || leaving.containsKey(child)) {
                        continue;
                    }
                    EnabledNestedMod.Why reason = this.findReasonToStay(child, leavingMod, leaving.keySet(),
                            alreadyOff);
                    if (reason == null) {
                        grew |= this.join(leaving, alreadyOff, child, new Reason.NestedIn(leavingMod));
                    } else {
                        retained.add(new EnabledNestedMod(child, leavingMod, reason));
                    }
                }
            }
        }
        this.tellWhyAskedGo(leaving);
        leaving.keySet().stream().filter(this.settings::isProtected).sorted()
                .forEach(mod -> refusals.add(new Refusal.Protected(mod)));
        if (refusals.stream().anyMatch(Refusal.Protected.class::isInstance)) {
            return new SwitchPlan(Direction.OFF, List.of(), Map.of(), List.of(), refusals);
        }
        return new SwitchPlan(Direction.OFF, this.sortDeepestFirst(leaving.keySet()), leaving, retained, refusals);
    }

    private void tellWhyAskedGo(Map<String, Reason> leaving) {
        for (Map.Entry<String, Reason> entry : leaving.entrySet()) {
            if (!(entry.getValue() instanceof Reason.Asked)) {
                continue;
            }
            for (String other : leaving.keySet()) {
                if (!other.equals(entry.getKey()) && this.graph.getDependents(other).contains(entry.getKey())) {
                    entry.setValue(new Reason.DependsOn(other));
                    break;
                }
            }
        }
    }

    public SwitchPlan planOn(Collection<String> asked, Set<String> off) {
        List<Refusal> refusals = new ArrayList<>();
        Map<String, Reason> coming = new LinkedHashMap<>();
        for (String mod : new TreeSet<>(asked)) {
            String id = this.graph.resolve(mod);
            if (this.graph.find(id).isEmpty()) {
                refusals.add(new Refusal.NotInstalled(mod));
            } else if (off.contains(id)) {
                coming.put(id, new Reason.Asked());
            }
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (String mod : new ArrayList<>(coming.keySet())) {
                for (String dependency : this.graph.getDependencies(mod)) {
                    if (off.contains(dependency) && !coming.containsKey(dependency)) {
                        coming.put(dependency, new Reason.NeededBy(mod));
                        grew = true;
                    } else if (this.graph.find(dependency).isEmpty() && !this.settings.builtIn().contains(dependency)) {
                        refusals.add(new Refusal.MissingDependency(mod, dependency));
                    }
                }
                for (String used : this.settings.uses().findUsedBy(mod)) {
                    if (off.contains(used) && !coming.containsKey(used) && this.graph.find(used).isPresent()) {
                        coming.put(used, new Reason.NeededBy(mod));
                        grew = true;
                    }
                }
                for (String child : this.graph.getChildrenOf(mod)) {
                    if (off.contains(child) && !coming.containsKey(child)) {
                        coming.put(child, this.returnsWith(child, mod) ? new Reason.NestedIn(mod)
                                : new Reason.NeededBy(mod));
                        grew = true;
                    }
                }
            }
        }
        refusals.addAll(this.findBreaks(coming.keySet(), off));
        if (!refusals.isEmpty()) {
            return new SwitchPlan(Direction.ON, List.of(), Map.of(), List.of(), List.copyOf(new LinkedHashSet<>(
                    refusals)));
        }
        List<String> order = new ArrayList<>(this.sortDeepestFirst(coming.keySet()));
        Collections.reverse(order);
        return new SwitchPlan(Direction.ON, order, coming, List.of(), List.of());
    }

    private boolean join(Map<String, Reason> leaving, Set<String> alreadyOff, String mod, Reason reason) {
        if (alreadyOff.contains(mod) || leaving.containsKey(mod)) {
            return false;
        }
        leaving.put(mod, reason);
        return true;
    }

    private EnabledNestedMod.@Nullable Why findReasonToStay(String child, String container, Set<String> leaving,
            Set<String> alreadyOff) {
        if (this.graph.isInstalledOnItsOwn(child)) {
            return EnabledNestedMod.Why.INSTALLED_ON_ITS_OWN;
        }
        Optional<String> parent = this.graph.find(child).flatMap(ModManifest::menuParent);
        if (parent.isPresent()) {
            return parent.get().equals(container) && this.settings.nestedJars() != NestedJars.NONE ? null
                    : parent.get().equals(container) ? EnabledNestedMod.Why.NESTED_JARS_SETTING_OFF
                            : EnabledNestedMod.Why.NOT_DECLARED_PART_OF_IT;
        }
        if (this.settings.nestedJars() != NestedJars.ALL) {
            return this.settings.nestedJars() == NestedJars.NONE ? EnabledNestedMod.Why.NESTED_JARS_SETTING_OFF
                    : EnabledNestedMod.Why.NOT_DECLARED_PART_OF_IT;
        }
        for (String other : this.graph.getContainers(child)) {
            if (!leaving.contains(other) && !alreadyOff.contains(other)) {
                return EnabledNestedMod.Why.ANOTHER_CONTAINER_STAYS;
            }
        }
        for (String dependent : this.graph.getDependents(child)) {
            if (!leaving.contains(dependent) && !alreadyOff.contains(dependent)) {
                return EnabledNestedMod.Why.NEEDED_BY_A_STAYING_MOD;
            }
        }
        return null;
    }

    private boolean returnsWith(String child, String container) {
        Optional<String> parent = this.graph.find(child).flatMap(ModManifest::menuParent);
        return switch (this.settings.nestedJars()) {
            case NONE -> false;
            case AUTO -> parent.map(container::equals).orElse(false);
            case ALL -> parent.map(container::equals).orElse(true);
        };
    }

    public List<Refusal> findOldDependencies(String mod) {
        List<Refusal> out = new ArrayList<>();
        ModManifest manifest = this.graph.find(mod).orElse(null);
        if (manifest == null) {
            return out;
        }
        manifest.getRelation(Relation.DEPENDS).forEach((otherId, ranges) -> {
            String other = this.graph.resolve(otherId);
            String present = this.graph.find(other).map(ModManifest::version).orElse(null);
            if (present != null && !ranges.isEmpty() && !this.settings.versions().matches(present, ranges)) {
                out.add(new Refusal.OldDependency(mod, other, String.join(" or ", ranges), present));
            }
        });
        return out;
    }

    private List<Refusal> findBreaks(Set<String> coming, Set<String> off) {
        List<Refusal> out = new ArrayList<>();
        SortedSet<String> onAfter = new TreeSet<>(this.graph.getIds());
        onAfter.removeAll(off);
        onAfter.addAll(coming);
        for (String mod : onAfter) {
            ModManifest manifest = this.graph.find(mod).orElseThrow();
            for (Relation relation : List.of(Relation.BREAKS, Relation.CONFLICTS)) {
                manifest.getRelation(relation).forEach((otherId, ranges) -> {
                    String other = this.graph.resolve(otherId);
                    boolean involvesComing = coming.contains(mod) || coming.contains(other);
                    if (involvesComing && onAfter.contains(other) && this.settings.versions().matches(
                            this.graph.find(other).map(ModManifest::version).orElse(""), ranges)) {
                        out.add(new Refusal.Breaks(mod, other));
                    }
                });
            }
        }
        return out;
    }

    private List<String> sortDeepestFirst(Set<String> mods) {
        Map<String, SortedSet<String>> before = new TreeMap<>();
        for (String mod : mods) {
            SortedSet<String> onTop = new TreeSet<>(this.graph.getDependents(mod));
            onTop.addAll(this.graph.getPatchersOf(mod).keySet());

            onTop.addAll(this.graph.getContainers(mod));
            onTop.retainAll(mods);
            onTop.remove(mod);
            before.put(mod, onTop);
        }
        List<String> order = new ArrayList<>();
        SortedSet<String> waiting = new TreeSet<>(mods);
        while (!waiting.isEmpty()) {
            String next = waiting.stream().filter(mod -> order.containsAll(before.get(mod))).findFirst()
                    .orElse(waiting.first());
            order.add(next);
            waiting.remove(next);
        }
        return order;
    }
}
