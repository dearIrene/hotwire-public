package dev.moon.hotwire.fabric.dev;

import dev.moon.hotwire.dev.BuildMerge;
import dev.moon.hotwire.dev.BuildNotes;
import dev.moon.hotwire.dev.DevBuild;
import dev.moon.hotwire.dev.DevBuildInfo;
import dev.moon.hotwire.dev.DevClassChange;
import dev.moon.hotwire.dev.DevDelta;
import dev.moon.hotwire.dev.DevEvent;
import dev.moon.hotwire.dev.DevState;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.MergeWords;
import dev.moon.hotwire.ui.Notice;
import dev.moon.hotwire.ui.Notices;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DevBuildEdits {
    private static final Logger LOGGER = LoggerFactory.getLogger(DevBuildEdits.class);

    private final Path folder;
    private final DevSession.Engine engine;

    DevBuildEdits(Path folder, DevSession.Engine engine) {
        this.folder = folder;
        this.engine = engine;
    }

    void accept(SessionMod mod, DevBuild built, int number) {
        mod.mixins = null;
        DevBuild before = mod.running;
        List<Path> staying = new ArrayList<>();

        boolean indexed = true;
        try {
            before.writeIndex();
        } catch (IOException e) {
            indexed = false;
            LOGGER.debug("[Hotwire] {} could not be saved for a roll back", before.getJar(), e);
        }

        Path launched = before.getJar().resolveSibling("build-0.jar");
        if (indexed && !before.getJar().equals(launched) && !before.getJar().equals(mod.startJar)
                && !before.getJar().equals(built.getJar())) {
            before.dropJar();
        }
        synchronized (mod) {
            mod.parents.putIfAbsent(number, mod.runningNumber);
            mod.saved.removeIf(saved -> saved.number() == number || saved.number() == mod.runningNumber);
            if (indexed) {
                mod.saved.addFirst(new SavedBuild(mod.runningNumber, before.getJar()));
            }
            this.dropOldest(mod);
            for (SavedBuild saved : mod.saved) {
                staying.add(saved.jar());
                staying.add(saved.jar().resolveSibling(saved.jar().getFileName() + ".idx"));
            }

            forgetGone(mod, number);
        }
        mod.lastRunning = mod.runningNumber;
        mod.running = built;
        mod.runningNumber = number;
        mod.builtAt = System.currentTimeMillis();
        mod.state = DevState.UP_TO_DATE;
        mod.line = "";

        Path start = built.getJar().resolveSibling("build-0.jar");
        staying.add(built.getJar());
        staying.add(built.getJar().resolveSibling(built.getJar().getFileName() + ".idx"));
        for (Path launch : List.of(start, mod.startJar)) {
            staying.add(launch);
            staying.add(launch.resolveSibling(launch.getFileName() + ".idx"));
        }
        DevSession.deleteBuildsBut(staying.toArray(Path[]::new));
        LOGGER.info("[Hotwire] {} runs build {} now (was {})", mod.mod.id(), built.getJar().getFileName(),
                before.getJar().getFileName());
    }

    private void dropOldest(SessionMod mod) {
        int over = mod.saved.size() - this.engine.getSavedBuilds();
        Iterator<SavedBuild> oldest = mod.saved.descendingIterator();
        while (over > 0 && oldest.hasNext()) {
            int number = oldest.next().number();
            if (!mod.pinned.contains(number) && !mod.isInUse(number)) {
                oldest.remove();
                over--;
            }
        }
    }

    void dropPastLimit(SessionMod mod) {
        synchronized (mod) {
            this.dropOldest(mod);
            forgetGone(mod, mod.runningNumber);
        }
    }

    void record(SessionMod mod, DevBuild built, int number, int madeOn, DevBuild.Diff diff, Map<String, byte[]> was,
            Map<String, byte[]> after, List<String> notes) {
        DevBuildInfo info = findInfo(number, madeOn, System.currentTimeMillis(), diff, was, after, notes, List.of());
        Set<String> put = new HashSet<>(diff.changedClasses());
        put.addAll(diff.addedClasses());
        Set<String> gone = new HashSet<>(diff.removedClasses());
        for (String file : diff.changedFiles()) {
            (built.hasEntry(file) ? put : gone).add(file);
        }
        synchronized (mod) {
            mod.infos.putIfAbsent(number, info);
            mod.deltas.putIfAbsent(number, new DevDelta(put, gone));
        }
    }

    private static DevBuildInfo findInfo(int number, int madeOn, long at, DevBuild.Diff diff, Map<String, byte[]> was,
            Map<String, byte[]> after, List<String> notes, List<Integer> merged) {
        List<DevClassChange> changed = new ArrayList<>();
        for (String entry : diff.changedClasses()) {
            BuildNotes.MethodChanges methods = BuildNotes.findMethodChanges(was.get(entry), after.get(entry));
            Map<String, List<Integer>> lines = new HashMap<>();
            methods.lines().forEach((name, span) -> lines.put(name, List.of(span[0], span[1])));
            String type = entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
            changed.add(new DevClassChange(toSimple(entry), type, methods.file() == null ? "" : methods.file(),
                    List.copyOf(methods.changed()), List.copyOf(methods.added()), List.copyOf(methods.removed()),
                    lines));
        }
        return new DevBuildInfo(number, madeOn, at, changed,
                diff.addedClasses().stream().map(DevBuildEdits::toSimple).toList(),
                diff.removedClasses().stream().map(DevBuildEdits::toSimple).toList(),
                diff.changedFiles().stream().map(file -> file.substring(file.lastIndexOf('/') + 1)).toList(), notes,
                merged);
    }

    private static void forgetGone(SessionMod mod, int running) {
        Set<Integer> there = new HashSet<>();
        mod.saved.forEach(saved -> there.add(saved.number()));
        there.add(running);
        mod.infos.keySet().retainAll(there);
        mod.deltas.keySet().retainAll(there);
        mod.names.keySet().retainAll(there);
        mod.pinned.retainAll(there);
    }

    private static String toSimple(String entry) {
        String plain = entry.endsWith(".class") ? entry.substring(0, entry.length() - ".class".length()) : entry;
        return plain.substring(plain.lastIndexOf('/') + 1);
    }

    void mergeNow(SessionMod mod, List<Integer> chosen, boolean anyway) {
        Map<Integer, Integer> parents;
        Map<Integer, DevDelta> deltas;
        Map<Integer, Path> jars = new HashMap<>();
        Set<Integer> pinned;
        Map<Integer, DevBuildInfo> infos;
        synchronized (mod) {
            parents = Map.copyOf(mod.parents);
            deltas = Map.copyOf(mod.deltas);
            pinned = Set.copyOf(mod.pinned);
            infos = Map.copyOf(mod.infos);
            mod.saved.forEach(saved -> jars.put(saved.number(), saved.jar()));
        }
        int running = mod.runningNumber;
        Set<Integer> there = new HashSet<>(jars.keySet());
        there.add(running);
        there.add(0);
        BuildMerge.Plan plan = BuildMerge.plan(chosen, parents, deltas, there, running, pinned);
        BuildMerge.Refusal refusal = plan.refusal();
        if (refusal != null) {
            this.tellMergeFailed(mod, MergeWords.describe(refusal));
            return;
        }
        if (!plan.conflicts().isEmpty() && !anyway) {
            this.tellMergeFailed(mod, Lang.get("hotwire.dev.merge.conflicts", plan.conflicts().size()));
            return;
        }
        DevBuild base = this.findBuild(mod, plan.base(), jars);
        if (base == null) {
            this.tellMergeFailed(mod, Lang.get("hotwire.dev.merge.unreadable", plan.base()));
            return;
        }
        int number;
        DevBuild merged;
        if (plan.isFast()) {
            number = plan.tip();
            merged = this.findBuild(mod, number, jars);
            if (merged == null) {
                this.tellMergeFailed(mod, Lang.get("hotwire.dev.merge.unreadable", number));
                return;
            }
        } else {
            number = mod.builds + 1;
            Map<Integer, DevBuild> read = new HashMap<>();
            Map<String, DevBuild> from = new HashMap<>();
            for (Map.Entry<String, Integer> source : plan.sources().entrySet()) {
                int build = source.getValue();
                DevBuild found = read.containsKey(build) ? read.get(build) : this.findBuild(mod, build, jars);
                if (found == null) {
                    this.tellMergeFailed(mod, Lang.get("hotwire.dev.merge.unreadable", build));
                    return;
                }
                read.put(build, found);
                from.put(source.getKey(), found);
            }
            Path jar = this.folder.resolve(mod.mod.id()).resolve("build-" + number + ".jar");
            try {
                merged = DevBuild.combine(base, from, plan.gone(), jar);
            } catch (IOException e) {
                DevSession.deleteQuietly(jar);
                DevSession.deleteQuietly(jar.resolveSibling(jar.getFileName() + ".idx"));
                LOGGER.warn("[Hotwire] builds {} of {} could not be merged", plan.builds(), mod.mod.id(), e);
                this.tellMergeFailed(mod, Lang.get("hotwire.dev.merge.unwritten"));
                return;
            }
            mod.builds = number;
        }
        DevBuildInfo info = findMergedInfo(plan, number, base, merged, infos);

        if (!plan.isFast() && merged != mod.running) {
            merged.dropJar();
        }
        synchronized (mod) {
            mod.saved.removeIf(saved -> plan.removed().contains(saved.number()));
            if (!plan.isFast()) {
                mod.saved.addFirst(new SavedBuild(number, merged.getJar()));
            }

            Map<Integer, Integer> moved = new HashMap<>();
            mod.parents.forEach((build, parent) -> {
                int up = parent;
                while (plan.removed().contains(up)) {
                    Integer next = mod.parents.get(up);
                    up = next == null ? -1 : next;
                }
                moved.put(build, up);
            });
            moved.put(number, plan.parent());
            mod.parents.clear();
            moved.forEach((build, parent) -> {
                if (parent >= 0 && !plan.removed().contains(build)) {
                    mod.parents.put(build, parent);
                }
            });
            mod.infos.put(number, info);
            mod.deltas.put(number, plan.delta());
            Map<String, Integer> threw = new HashMap<>();
            for (int build : plan.builds()) {
                Map<String, Integer> seen = mod.throwsByBuild.get(build);
                if (seen != null) {
                    seen.forEach((what, count) -> threw.merge(what, count, Integer::sum));
                }
            }
            plan.removed().forEach(mod.throwsByBuild::remove);
            if (!threw.isEmpty()) {
                mod.throwsByBuild.put(number, threw);
            }
            mod.autoRolled.removeAll(plan.removed());

            List<DevEvent> events = new ArrayList<>(mod.events);
            mod.events.clear();
            for (DevEvent event : events) {
                mod.events.addLast(plan.removed().contains(event.build()) ? new DevEvent(event.at(), event.kind(),
                        event.title(), event.lines(), number) : event);
            }
            forgetGone(mod, mod.runningNumber);
        }
        for (int build : plan.removed()) {
            Path jar = jars.get(build);
            if (jar != null) {
                DevSession.deleteQuietly(jar);
                DevSession.deleteQuietly(jar.resolveSibling(jar.getFileName() + ".idx"));
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add(Lang.get("hotwire.dev.merge.made_on", MergeWords.describeBuild(plan.parent())));
        if (!plan.conflicts().isEmpty()) {
            lines.add(Lang.get("hotwire.dev.merge.kept_newer", plan.conflicts().size()));
        }
        if (!plan.removed().isEmpty()) {
            lines.add(Lang.get(plan.removed().size() == 1 ? "hotwire.dev.merge.gone.one" : "hotwire.dev.merge.gone",
                    MergeWords.list(List.copyOf(new TreeSet<>(plan.removed())))));
        }
        String title = Lang.get("hotwire.dev.merged", MergeWords.list(plan.builds()), number);
        mod.addEvent(new DevEvent(System.currentTimeMillis(), DevEvent.Kind.MERGED, title, lines, number));
        LOGGER.info("[Hotwire] {}: merged builds {} into build {}", mod.mod.id(), plan.builds(), number);
    }

    private static DevBuildInfo findMergedInfo(BuildMerge.Plan plan, int number, DevBuild base, DevBuild merged,
            Map<Integer, DevBuildInfo> infos) {
        DevBuild.Diff diff = base.diff(merged);
        Map<String, byte[]> was = base.readEntries(diff.changedClasses());
        Map<String, byte[]> after = merged.readEntries(DevSession.combine(diff.changedClasses(), diff.addedClasses()));
        Set<String> notes = new LinkedHashSet<>();
        long at = 0;
        for (int build : plan.builds()) {
            DevBuildInfo info = infos.get(build);
            if (info != null) {
                notes.addAll(info.notes());
                at = Math.max(at, info.at());
            }
        }
        if (at == 0) {
            at = System.currentTimeMillis();
        }
        DevBuildInfo own = findInfo(number, plan.parent(), at, diff, was, after, List.copyOf(notes), plan.builds());
        DevBuildInfo before = infos.get(plan.base());
        if (!plan.removed().contains(plan.base()) || before == null) {
            return own;
        }
        Map<String, Map<String, List<Integer>>> lines = new HashMap<>();
        after.forEach((entry, bytes) -> lines.put(entry.substring(0, entry.length() - ".class".length())
                .replace('/', '.'), BuildNotes.findMethodLines(bytes)));
        return BuildMerge.combine(before, own, number, plan.parent(), at, plan.builds(), lines);
    }

    private @Nullable DevBuild findBuild(SessionMod mod, int number, Map<Integer, Path> jars) {
        if (number == mod.runningNumber) {
            return mod.running;
        }
        Path jar = jars.get(number);
        if (jar == null && number == 0) {
            jar = this.folder.resolve(mod.mod.id()).resolve("build-0.jar");
        }
        return jar == null ? null : DevBuild.readIndex(jar);
    }

    private void tellMergeFailed(SessionMod mod, String why) {
        String title = Lang.get("hotwire.dev.merge.failed", mod.mod.name());
        mod.addEvent(new DevEvent(System.currentTimeMillis(), DevEvent.Kind.FAILED, title, List.of(why)));
        LOGGER.info("[Hotwire] {}: {} {}", mod.mod.id(), title, why);
        Notices.show(Notice.ofBad(title, why));
    }

    void copyNow(SessionMod mod, int build) {
        Path from;
        synchronized (mod) {
            from = mod.saved.stream().filter(saved -> saved.number() == build).map(SavedBuild::jar).findFirst()
                    .orElse(null);
        }
        boolean running = build == mod.runningNumber;
        if (running) {
            from = mod.running.getJar();
        }
        if (from == null) {
            return;
        }
        int number = mod.builds + 1;
        Path jar = this.folder.resolve(mod.mod.id()).resolve("build-" + number + ".jar");
        Path index = jar.resolveSibling(jar.getFileName() + ".idx");
        try {
            if (running) {
                mod.running.writeIndex();
            }

            if (Files.isRegularFile(from)) {
                link(from, jar);
            }
            link(from.resolveSibling(from.getFileName() + ".idx"), index);

            DevBuild copied = DevBuild.readIndex(jar);
            if (copied != null) {
                copied.dropJar();
            }
        } catch (IOException e) {
            DevSession.deleteQuietly(jar);
            DevSession.deleteQuietly(index);
            LOGGER.warn("[Hotwire] build {} of {} could not be copied", build, mod.mod.id(), e);
            Notices.show(Notice.ofBad(Lang.get("hotwire.dev.copy.failed", build), ""));
            return;
        }
        mod.builds = number;
        synchronized (mod) {
            mod.saved.addFirst(new SavedBuild(number, jar));
            Integer parent = mod.parents.get(build);
            if (parent != null) {
                mod.parents.put(number, parent);
            }
            DevBuildInfo info = mod.infos.get(build);
            if (info != null) {
                mod.infos.put(number, new DevBuildInfo(number, info.madeOn(), info.at(), info.classes(),
                        info.addedClasses(), info.removedClasses(), info.files(), info.notes(), info.merged(),
                        info.git(), info.uncommitted()));
            }
            DevDelta delta = mod.deltas.get(build);
            if (delta != null) {
                mod.deltas.put(number, delta);
            }
            String name = mod.names.get(build);
            mod.names.put(number, Lang.get("hotwire.dev.copy.name", name == null
                    ? Lang.get("hotwire.dev.pick.build", build) : name));
        }
        mod.addEvent(new DevEvent(System.currentTimeMillis(), DevEvent.Kind.MERGED,
                Lang.get("hotwire.dev.copied_build", build, number), List.of(), number));
    }

    private static void link(Path from, Path to) throws IOException {
        Files.deleteIfExists(to);
        try {
            Files.createLink(to, from);
        } catch (IOException | UnsupportedOperationException e) {
            Files.copy(from, to);
        }
    }

    List<String> findNotes(SessionMod mod, DevBuild.Diff diff, Map<String, byte[]> was,
            Map<String, byte[]> after) {
        List<String> notes = new ArrayList<>();
        Map<String, byte[]> changed = new TreeMap<>(after);
        changed.keySet().retainAll(diff.changedClasses());
        this.engine.redefineElsewhere(changed).forEach(name -> notes.add(Lang.get("hotwire.dev.elsewhere", name)));
        Set<String> names = new HashSet<>();
        for (String entry : diff.changedClasses()) {
            String binary = entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
            String simple = binary.substring(binary.lastIndexOf('.') + 1);
            names.add(binary);
            if (mod.starts.contains(binary) && BuildNotes.isStartChanged(was.get(entry), after.get(entry))) {
                notes.add(Lang.get("hotwire.dev.start_changed", simple));
            }
            if (!DevSession.isFullRedefinition() && !BuildNotes.findAddedFields(was.get(entry),
                    after.get(entry)).isEmpty()) {
                notes.add(Lang.get("hotwire.dev.fields_added", simple));
            }
        }
        if (DevSession.isFullRedefinition()) {
            ConfigRefresh.refresh(this.engine.findLoaded(names)).forEach(name -> notes.add(
                    Lang.get("hotwire.dev.config.refreshed", name)));
        }
        return notes;
    }

    static void collectStore(Path modFolder) {
        try {
            DevBuild.collect(modFolder);
        } catch (IOException e) {
            LOGGER.debug("[Hotwire] the stored files of {} could not be collected", modFolder.getFileName(), e);
        }
    }
}
