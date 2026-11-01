package dev.moon.hotwire.fabric.update;

import dev.moon.hotwire.fabric.ModPolicy;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.switching.ModMixins;
import dev.moon.hotwire.update.JarLinks;
import dev.moon.hotwire.update.UpdatePlan;
import dev.moon.hotwire.update.UpdatePlan.UpdateNotice;
import java.io.IOException;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class UpdateClashes {
    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateClashes.class);
    private final ModUpdater owner;

    UpdateClashes(ModUpdater owner) {
        this.owner = owner;
    }

    JarLinks.GameClassReader makeGameClassReader() {
        return new JarLinks.GameClassReader(this.owner.engine.getApplier().getRebuilder().copy()::findReadable);
    }

    static Set<String> findEntrypointsFor(ModManifest manifest, Set<String> waitingIds) {
        Set<String> classes = new HashSet<>();
        manifest.entrypoints().forEach((key, entries) -> {
            int colon = key.indexOf(':');
            if (colon > 0 && waitingIds.contains(key.substring(0, colon))) {
                entries.forEach(entry -> {
                    String value = entry.value();
                    int member = value.indexOf("::");
                    classes.add((member < 0 ? value : value.substring(0, member)).replace('.', '/'));
                });
            }
        });
        return classes;
    }

    @Nullable String findUnmetRange(ModManifest manifest, Map<String, ModManifest> comingIn) {
        for (Map.Entry<String, List<String>> wants : manifest.getRelation(ModManifest.Relation.DEPENDS).entrySet()) {
            String resolved = this.owner.engine.getGraph().resolve(wants.getKey());
            ModManifest coming = comingIn.get(resolved);
            String version = coming != null ? coming.version() : this.owner.engine.findVersionOf(wants.getKey());
            if (version != null && !this.owner.engine.satisfies(version, wants.getValue())) {
                return "it needs " + wants.getKey() + " " + String.join(" or ", wants.getValue()) + ", and this batch"
                        + " leaves " + version;
            }
        }
        return null;
    }

    void readNamed(ModUpdater.SortResult.Planned planned, Set<String> waitingIds, Set<String> mayDiffer,
            Map<Path, Map<String, Set<String>>> named) {
        named.computeIfAbsent(planned.jar(), jar -> {
            try {
                return JarLinks.readNamed(jar, findEntrypointsFor(planned.manifest(), waitingIds), mayDiffer);
            } catch (IOException e) {
                LOGGER.info("[Hotwire] {} could not be read through: {}", jar.getFileName(), e.toString());
                return Map.of();
            }
        });
    }

    @Nullable String findClash(ModUpdater.SortResult.Planned planned, Set<String> waiting, Map<Path, Map<String,
            Set<String>>> named,
            JarLinks.GameClassReader running, Set<String> goingIn, Map<String, ModManifest> comingIn) {
        String unmet = this.findUnmetRange(planned.manifest(), comingIn);
        for (CarriedUpdates.Library carried : planned.whole().bundled()) {
            unmet = unmet != null ? unmet : this.findUnmetRange(carried.manifest(), comingIn);
        }
        if (unmet != null) {
            return unmet;
        }
        String missing = JarLinks.findMissing(named.getOrDefault(planned.jar(), Map.of()), waiting, running);
        if (missing != null) {
            return "it needs " + missing + ", which comes with an update that waits";
        }
        List<UpdatePlan> plans = new ArrayList<>(List.of(planned.whole().plan()));
        planned.whole().bundled().forEach(carried -> plans.add(carried.plan()));
        for (UpdatePlan plan : plans) {
            for (UpdateNotice notice : plan.notices()) {
                if (notice instanceof UpdateNotice.AbstractsGained abstracts) {
                    String lacking = this.findLacking(abstracts.methods(), goingIn, findPatchedBy(planned));
                    if (lacking != null) {
                        return lacking;
                    }
                }
            }
        }
        return null;
    }

    static Set<String> findPatchedBy(ModUpdater.SortResult.Planned planned) {
        Set<String> patched = new HashSet<>(ModMixins.of(planned.read(), ModPolicy.getSide()).targets());
        planned.whole().bundled().forEach(carried ->
                patched.addAll(ModMixins.of(carried.read(), ModPolicy.getSide()).targets()));
        return patched;
    }

    @Nullable String findLacking(List<String> methods, Set<String> goingIn, Set<String> patched) {
        Class<?>[] loaded = this.owner.engine.getApplier().getRebuilder().getLoadedClasses();
        for (String method : methods) {
            String[] parts = method.split(" ", -1);
            Class<?> owner = this.owner.engine.getClasses().findLoaded(parts[0]);
            if (owner == null) {
                continue;
            }
            for (Class<?> one : loaded) {
                if (one != owner && owner.isAssignableFrom(one) && !one.isInterface()
                        && !Modifier.isAbstract(one.getModifiers())
                        && !goingIn.contains(one.getName().replace('.', '/')) && !isPatched(one, patched)
                        && !hasMethodBody(one, parts[1], parts[2])) {
                    return one.getName() + " has no " + parts[1] + ", which " + parts[0] + " now asks of it";
                }
            }
        }
        return null;
    }

    static boolean isPatched(Class<?> type, Set<String> patched) {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            if (patched.contains(at.getName().replace('.', '/'))) {
                return true;
            }
        }
        return false;
    }

    static boolean hasMethodBody(Class<?> type, String name, String desc) {
        try {
            for (Class<?> at = type; at != null; at = at.getSuperclass()) {
                for (Method method : at.getDeclaredMethods()) {
                    if (method.getName().equals(name) && !Modifier.isAbstract(method.getModifiers())
                            && MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                            .toMethodDescriptorString().equals(desc)) {
                        return true;
                    }
                }
            }
            for (Method method : type.getMethods()) {
                if (method.getName().equals(name) && method.isDefault()
                        && MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                        .toMethodDescriptorString().equals(desc)) {
                    return true;
                }
            }
            return false;
        } catch (LinkageError e) {
            return true;
        }
    }
}
