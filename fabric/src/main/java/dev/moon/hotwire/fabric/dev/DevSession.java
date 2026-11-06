package dev.moon.hotwire.fabric.dev;

import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.dev.BuildMerge;
import dev.moon.hotwire.dev.BuildNotes;
import dev.moon.hotwire.dev.DevBridge;
import dev.moon.hotwire.dev.DevBuild;
import dev.moon.hotwire.dev.DevBuildInfo;
import dev.moon.hotwire.dev.DevClassChange;
import dev.moon.hotwire.dev.DevCrash;
import dev.moon.hotwire.dev.DevDelta;
import dev.moon.hotwire.dev.DevEvent;
import dev.moon.hotwire.dev.DevHost;
import dev.moon.hotwire.dev.DevLink;
import dev.moon.hotwire.dev.DevMixin;
import dev.moon.hotwire.dev.DevModView;
import dev.moon.hotwire.dev.DevState;
import dev.moon.hotwire.dev.WorldLeaks;
import dev.moon.hotwire.fabric.rebuild.GameClasses;
import dev.moon.hotwire.fabric.update.ModUpdater;
import dev.moon.hotwire.redefine.ClassRebuilder;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.MergeWords;
import dev.moon.hotwire.ui.Notice;
import dev.moon.hotwire.ui.Notices;
import dev.moon.hotwire.ui.UpdateMethod;
import dev.moon.hotwire.ui.UpdatePreview;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DevSession implements DevHost, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DevSession.class);
    private static final int MOST_EVENTS = 40;
    private static final int MOST_CRASHES = 20;

    private record Kept(int number, Path jar) {
    }

    private static final int METHOD_LINES = 4;

    private static final float CRASH_REST_SECONDS = 10;

    private static final long REMOVAL_WAIT_MILLIS = 2000;

    private static final long HALF_WAIT_MILLIS = 300;
    private static final int HALF_TRIES = 10;
    private static volatile @Nullable DevSession current;

    private static final Map<String, Start> STARTS = new ConcurrentHashMap<>();

    private record Start(DevMod mod, DevBuild build) {
    }

    public record Restart(@Nullable String failure, List<String> leftOver) {
        public Restart {
            leftOver = List.copyOf(leftOver);
        }
    }

    public interface Engine {
        UpdatePreview preview(Path jar);

        ModUpdater.Result updateInPlace(Path jar);

        String describe(ModUpdater.Result result);

        Restart restart(String id);

        boolean isEveryReloadShown();

        boolean isAutoRollBackOn();

        int getKeptBuilds();

        List<Class<?>> findLoaded(Set<String> names);

        List<DevMixin> findMixins(String id);

        List<String> redefineElsewhere(Map<String, byte[]> classes);
    }

    private static final int NAME_MOST = 40;

    private static final class Mod {
        final DevMod mod;

        final Object taking = new Object();
        volatile DevBuild running;
        volatile int runningNumber;

        final Deque<Kept> kept = new ArrayDeque<>();
        final Map<Integer, Integer> parents = new HashMap<>();

        final Map<Integer, DevBuildInfo> infos = new HashMap<>();

        final Map<Integer, Map<String, Integer>> throwsByBuild = new HashMap<>();
        final Set<Integer> autoRolled = new HashSet<>();

        final Map<Integer, DevDelta> deltas = new HashMap<>();
        final Map<Integer, String> names = new HashMap<>();
        final Set<Integer> pinned = new HashSet<>();
        volatile long builtAt = System.currentTimeMillis();

        volatile boolean crashTold;
        int builds;

        long goneSince;

        int halfTries;

        final Set<String> starts;
        volatile DevState state = DevState.UP_TO_DATE;
        volatile String line = "";
        final Deque<DevEvent> events = new ArrayDeque<>();
        final Map<String, DevCrash> crashes = new LinkedHashMap<>();

        volatile @Nullable List<DevMixin> mixins;

        Mod(DevMod mod, DevBuild running) {
            this.mod = mod;
            this.running = running;
            this.starts = mod.findStartClasses();
        }
    }

    private final Map<String, Mod> mods = new LinkedHashMap<>();
    private final Path folder;
    private final Tasks tasks;
    private final Engine engine;
    private final DevWatch watch;
    private volatile boolean watching = true;

    private DevSession(Path folder, Tasks tasks, Engine engine) throws IOException {
        this.folder = folder;
        this.tasks = tasks;
        this.engine = engine;
        List<DevMod> watched = new ArrayList<>();
        for (Start start : STARTS.values()) {
            this.mods.put(start.mod().id(), new Mod(start.mod(), start.build()));
            watched.add(start.mod());
        }
        this.watch = new DevWatch(watched, this::onBuilt);
    }

    public static Path takeStart(DevMod mod, Path folder) throws IOException {
        Path jar = folder.resolve(mod.id()).resolve("build-0.jar");
        deleteBuildsBut(jar, jar.resolveSibling(jar.getFileName() + ".idx"));
        DevBuild before = DevBuild.readIndex(jar);
        DevBuild build = DevBuild.take(mod.folders(), jar, before);
        if (build != before) {
            build.writeIndex();
        }
        STARTS.put(mod.id(), new Start(mod, build));
        return build.getJar();
    }

    private static void deleteBuildsBut(Path... staying) {
        Path parent = staying[0].getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return;
        }

        Set<Path> stays = Set.copyOf(Arrays.asList(staying));
        try (DirectoryStream<Path> builds = Files.newDirectoryStream(parent, "build-*")) {
            for (Path build : builds) {
                if (!stays.contains(build)) {
                    deleteQuietly(build);
                }
            }
        } catch (IOException e) {
            LOGGER.debug("[Hotwire] the builds in {} could not be listed", parent, e);
        }
    }

    public static @Nullable DevSession start(Path folder, Tasks tasks, Engine engine) {
        if (STARTS.isEmpty()) {
            return null;
        }
        try {
            DevSession session = new DevSession(folder, tasks, engine);
            current = session;

            List<Path> projects = new ArrayList<>();
            for (Start start : STARTS.values()) {
                for (Path built : start.mod().folders()) {
                    Path project = DevMod.findProject(built);
                    if (project != null && !projects.contains(project)) {
                        projects.add(project);
                    }
                }
            }
            Path log = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");
            DevLink.set(new DevBridge(session, () -> log, projects));
            GameClasses.setOriginalsFallback(DevSession::readRunningClass);
            LOGGER.info("[Hotwire] developer mode watches the build folders of {}", STARTS.keySet());
            return session;
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] developer mode could not watch the build folders", e);
            return null;
        }
    }

    private static byte @Nullable [] readRunningClass(String internal) {
        DevSession session = current;
        if (session == null) {
            return null;
        }
        String binary = internal.replace('/', '.');
        for (Mod mod : session.mods.values()) {
            DevBuild running = mod.running;
            if (running.hasClass(binary)) {
                return running.readClass(binary);
            }
        }
        return null;
    }

    public static boolean isDevMod(String id) {
        return STARTS.containsKey(id);
    }

    private static volatile boolean fullRedefinition;

    public static boolean takesShapes(String id) {
        return fullRedefinition && isDevMod(id);
    }

    public static void useFullRedefinition(boolean full) {
        fullRedefinition = full;
        if (full) {
            ClassRebuilder.setWholeClasses(name -> {
                String binary = name.replace('/', '.');
                for (Start start : STARTS.values()) {
                    if (start.build().hasClass(binary)) {
                        return true;
                    }
                }
                DevSession session = current;
                return session != null && session.isOwnClass(binary);
            });
        }
    }

    private boolean isOwnClass(String binary) {
        for (Mod mod : this.mods.values()) {
            if (mod.running.hasClass(binary)) {
                return true;
            }
        }
        return false;
    }

    public static void onCrash(String id, Throwable thrown, String what, String where, String source, int line) {
        DevSession session = current;
        Mod mod = session == null ? null : session.mods.get(id);
        if (mod == null) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean first;
        String kind;
        String place;
        int count;
        synchronized (mod) {
            DevCrash like = where.isEmpty() ? findLike(mod, what) : null;
            kind = like == null ? what : like.what();
            place = like == null ? where : like.where();
            String file = like == null ? source : like.source();
            int at = like == null ? line : like.line();
            String key = kind + " @ " + place;
            DevCrash before = mod.crashes.remove(key);
            first = before == null;
            count = before == null ? 1 : before.count() + 1;
            mod.crashes.put(key, before == null ? new DevCrash(kind, place, 1, now, now, file, at)
                    : new DevCrash(kind, place, before.count() + 1, before.firstAt(), now, file, at));
            while (mod.crashes.size() > MOST_CRASHES) {
                mod.crashes.remove(mod.crashes.keySet().iterator().next());
            }
            if (first) {
                add(mod, new DevEvent(now, DevEvent.Kind.CRASH, Lang.get("hotwire.dev.crash", mod.mod.name()),
                        List.of(kind, place)));
            }
        }
        if (first) {
            LOGGER.error("[Hotwire] {} threw at {}; the game keeps going, so fix it and build again", id, place,
                    thrown);
        }

        int build = mod.runningNumber;
        Integer parent;
        boolean back;
        synchronized (mod) {
            mod.throwsByBuild.computeIfAbsent(build, ignored -> new HashMap<>()).merge(
                    Lang.get("hotwire.dev.crash.at", kind, place), 1, Integer::sum);
            parent = mod.parents.get(build);
            Integer to = parent;
            back = session.engine.isAutoRollBackOn() && to != null && build != 0
                    && mod.kept.stream().anyMatch(kept -> kept.number() == to) && mod.autoRolled.add(build);
        }

        if (back && parent != null) {
            Notices.show(Notice.ofAsk(Lang.get("hotwire.dev.auto_back", mod.mod.name(), parent),
                    Lang.get("hotwire.dev.auto_back.why", build, kind, place)));
            LOGGER.warn("[Hotwire] {} build {} threw at {}: rolled back to build {}", id, build, place, parent);
            session.rollBack(id, parent);
            return;
        }
        Notice card = findCrashCard(mod, count == 1 ? Lang.get("hotwire.dev.crash.at", kind, place)
                : Lang.get("hotwire.dev.crash.times", kind, place, count));
        if (mod.crashTold) {
            Notices.replace(card);
        } else {
            mod.crashTold = true;
            Notices.show(card);
        }
    }

    private static Map<Integer, List<String>> findThrows(Mod mod) {
        Map<Integer, List<String>> found = new HashMap<>();
        synchronized (mod) {
            mod.throwsByBuild.forEach((build, seen) -> found.put(build, seen.entrySet().stream()
                    .map(each -> each.getValue() == 1 ? each.getKey()
                            : Lang.get("hotwire.dev.report.times", each.getKey(), each.getValue()))
                    .toList()));
        }
        return found;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void onBuilt(DevMod built) {
        Mod mod = this.mods.get(built.id());
        if (mod == null) {
            return;
        }
        if (!this.watching) {
            mod.state = DevState.WAITING;
            mod.line = Lang.get("hotwire.dev.waiting");
            return;
        }
        this.tasks.submit("taking " + built.id() + "'s build", () -> {
            this.take(mod);
            return null;
        });
    }

    private void take(Mod mod) {
        synchronized (mod.taking) {
            int number = mod.builds + 1;
            Path jar = this.folder.resolve(mod.mod.id()).resolve("build-" + number + ".jar");
            DevBuild built;
            try {
                built = DevBuild.take(mod.mod.folders(), jar, mod.running);
            } catch (IOException e) {
                deleteQuietly(jar);
                if (mod.halfTries++ < HALF_TRIES) {
                    this.watch.lookAgain(mod.mod, TimeUnit.MILLISECONDS.toNanos(HALF_WAIT_MILLIS));
                    return;
                }
                mod.halfTries = 0;

                String file = e instanceof FileSystemException locked && locked.getFile() != null
                        ? String.valueOf(Path.of(locked.getFile()).getFileName()) : String.valueOf(e.getMessage());
                this.fail(mod, List.of(Lang.get("hotwire.dev.unreadable_file", file)));
                return;
            }

            if (built == mod.running) {
                return;
            }
            DevBuild.Diff diff = mod.running.diff(built);
            long now = System.currentTimeMillis();
            if (diff.removedClasses().isEmpty()) {
                mod.goneSince = 0;
            } else if (mod.goneSince == 0 || now - mod.goneSince < REMOVAL_WAIT_MILLIS) {
                if (mod.goneSince == 0) {
                    mod.goneSince = now;
                }
                deleteQuietly(jar);
                this.watch.lookAgain(mod.mod, TimeUnit.MILLISECONDS.toNanos(REMOVAL_WAIT_MILLIS));
                return;
            }
            if (diff.isEmpty()) {
                deleteQuietly(jar);
                if (mod.state == DevState.WAITING) {
                    mod.state = DevState.UP_TO_DATE;
                }
                return;
            }

            Map<String, byte[]> classes = built.readEntries(combine(diff.changedClasses(), diff.addedClasses()));
            String half = classes.entrySet().stream().filter(entry -> !BuildNotes.isWhole(entry.getValue()))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            if (half != null && mod.halfTries++ < HALF_TRIES) {
                deleteQuietly(jar);
                this.watch.lookAgain(mod.mod, TimeUnit.MILLISECONDS.toNanos(HALF_WAIT_MILLIS));
                return;
            }
            mod.halfTries = 0;
            if (half != null) {
                deleteQuietly(jar);
                this.fail(mod, List.of(Lang.get("hotwire.dev.half", half)));
                return;
            }
            DevBuild before = mod.running;

            Map<String, byte[]> was = before.readEntries(diff.changedClasses());
            mod.builds = number;
            mod.state = DevState.LOADING;
            mod.line = Lang.get("hotwire.dev.loading");
            long began = System.nanoTime();
            UpdatePreview preview = this.engine.preview(jar);
            if (preview instanceof UpdatePreview.Ready ready && ready.method() == UpdateMethod.IN_PLACE) {
                clearCrashCard(mod);
                ModUpdater.Result result = this.engine.updateInPlace(jar);
                if (result instanceof ModUpdater.Result.Updated) {
                    int madeOn = mod.runningNumber;
                    this.accept(mod, built, number);
                    if (diff.hasClasses()) {
                        List<String> lines = new ArrayList<>();

                        SortedSet<String> unnamed = new TreeSet<>(diff.changedClasses());
                        for (String entry : diff.changedClasses()) {
                            SortedSet<String> methods = BuildNotes.findChangedMethods(was.get(entry),
                                    classes.get(entry));
                            if (!methods.isEmpty() && lines.size() < METHOD_LINES) {
                                String simple = entry.substring(entry.lastIndexOf('/') + 1, entry.length() - 6);
                                lines.add(Lang.get("hotwire.dev.methods", simple, String.join(", ", methods)));
                                unnamed.remove(entry);
                            }
                        }
                        if (!unnamed.isEmpty()) {
                            lines.add(Lang.get("hotwire.dev.changed", String.join(", ", BuildWords.list(unnamed))));
                        }
                        if (!diff.addedClasses().isEmpty()) {
                            lines.add(Lang.get("hotwire.dev.added",
                                    String.join(", ", BuildWords.list(diff.addedClasses()))));
                        }
                        List<String> notes = this.findNotes(mod, diff, was, classes);
                        lines.addAll(notes);
                        String took = Lang.get("hotwire.dev.took", BuildWords.describeClasses(diff),
                                BuildWords.findSeconds(began));
                        String detail = notes.isEmpty() ? took : notes.getFirst();

                        lines.add(Lang.get("hotwire.dev.build.made_on", madeOn));
                        this.record(mod, built, number, madeOn, diff, was, classes, notes);
                        this.tell(mod, DevEvent.Kind.IN, Lang.get("hotwire.dev.in.build", mod.mod.name(), number),
                                detail, lines);
                    } else {
                        String took = Lang.get("hotwire.dev.took", BuildWords.describeFiles(diff),
                                BuildWords.findSeconds(began));
                        this.tell(mod, DevEvent.Kind.ASSETS, Lang.get("hotwire.dev.assets.build", mod.mod.name(),
                                number), took, List.of(Lang.get("hotwire.dev.changed",
                                String.join(", ", BuildWords.list(diff.changedFiles()))),
                                Lang.get("hotwire.dev.build.made_on", madeOn)));
                        this.record(mod, built, number, madeOn, diff, Map.of(), Map.of(), List.of());
                    }
                } else {
                    this.fail(mod, List.of(this.engine.describe(result)));
                }
            } else if (preview instanceof UpdatePreview.Ready ready) {
                mod.state = DevState.NEEDS_RESTART;
                mod.line = Lang.get("hotwire.dev.needs_restart.line");
                this.tell(mod, DevEvent.Kind.NEEDS_RESTART, Lang.get("hotwire.dev.needs_restart", mod.mod.name()),
                        ready.lines().isEmpty() ? "" : ready.lines().getFirst(), ready.lines());
            } else {
                this.fail(mod, List.of(BuildWords.describePreview(preview)));
            }
        }
    }

    private List<String> findNotes(Mod mod, DevBuild.Diff diff, Map<String, byte[]> was, Map<String, byte[]> after) {
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
            if (!fullRedefinition && !BuildNotes.findAddedFields(was.get(entry), after.get(entry)).isEmpty()) {
                notes.add(Lang.get("hotwire.dev.fields_added", simple));
            }
        }
        if (fullRedefinition) {
            ConfigRefresh.refresh(this.engine.findLoaded(names)).forEach(name -> notes.add(
                    Lang.get("hotwire.dev.config.refreshed", name)));
        }
        return notes;
    }

    private static SortedSet<String> combine(SortedSet<String> a, SortedSet<String> b) {
        SortedSet<String> both = new TreeSet<>(a);
        both.addAll(b);
        return both;
    }

    private void accept(Mod mod, DevBuild built, int number) {
        mod.mixins = null;
        DevBuild before = mod.running;
        List<Path> keep = new ArrayList<>();

        boolean indexed = true;
        try {
            before.writeIndex();
        } catch (IOException e) {
            indexed = false;
            LOGGER.debug("[Hotwire] {} could not be kept for a roll back", before.getJar(), e);
        }
        synchronized (mod) {
            mod.parents.putIfAbsent(number, mod.runningNumber);
            mod.kept.removeIf(kept -> kept.number() == number || kept.number() == mod.runningNumber);
            if (indexed) {
                mod.kept.addFirst(new Kept(mod.runningNumber, before.getJar()));
            }

            int over = mod.kept.size() - this.engine.getKeptBuilds();
            Iterator<Kept> oldest = mod.kept.descendingIterator();
            while (over > 0 && oldest.hasNext()) {
                if (!mod.pinned.contains(oldest.next().number())) {
                    oldest.remove();
                    over--;
                }
            }
            for (Kept kept : mod.kept) {
                keep.add(kept.jar());
                keep.add(kept.jar().resolveSibling(kept.jar().getFileName() + ".idx"));
            }

            forgetGone(mod, number);
        }
        mod.running = built;
        mod.runningNumber = number;
        mod.builtAt = System.currentTimeMillis();
        mod.state = DevState.UP_TO_DATE;
        mod.line = "";

        Path start = built.getJar().resolveSibling("build-0.jar");
        keep.add(built.getJar());
        keep.add(start);
        keep.add(start.resolveSibling("build-0.jar.idx"));
        deleteBuildsBut(keep.toArray(Path[]::new));
        LOGGER.info("[Hotwire] {} runs build {} now (was {})", mod.mod.id(), built.getJar().getFileName(),
                before.getJar().getFileName());
    }

    private void record(Mod mod, DevBuild built, int number, int madeOn, DevBuild.Diff diff, Map<String, byte[]> was,
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
                diff.addedClasses().stream().map(DevSession::toSimple).toList(),
                diff.removedClasses().stream().map(DevSession::toSimple).toList(),
                diff.changedFiles().stream().map(file -> file.substring(file.lastIndexOf('/') + 1)).toList(), notes,
                merged);
    }

    private static void forgetGone(Mod mod, int running) {
        Set<Integer> there = new HashSet<>();
        mod.kept.forEach(kept -> there.add(kept.number()));
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

    private static @Nullable DevCrash findLike(Mod mod, String what) {
        for (DevCrash seen : List.copyOf(mod.crashes.values()).reversed()) {
            if (seen.what().equals(what) || seen.what().startsWith(what + ":")) {
                return seen;
            }
        }
        return null;
    }

    private static Notice findCrashCard(Mod mod, String detail) {
        return Notice.ofBad(Lang.get("hotwire.dev.crash", mod.mod.name()), detail).makeResting(CRASH_REST_SECONDS);
    }

    private static void clearCrashCard(Mod mod) {
        mod.crashTold = false;
        Notices.drop(findCrashCard(mod, ""));
    }

    private void fail(Mod mod, List<String> why) {
        mod.state = DevState.FAILED;
        mod.line = why.isEmpty() ? "" : why.getFirst();
        this.tell(mod, DevEvent.Kind.FAILED, Lang.get("hotwire.dev.failed", mod.mod.name()), mod.line, why);
    }

    private void tell(Mod mod, DevEvent.Kind kind, String title, String detail, List<String> lines) {
        boolean in = kind == DevEvent.Kind.IN || kind == DevEvent.Kind.ASSETS;
        add(mod, new DevEvent(System.currentTimeMillis(), kind, title, lines, in ? mod.runningNumber : -1));
        LOGGER.info("[Hotwire] {}: {} {}", mod.mod.id(), title, detail);
        boolean problem = kind == DevEvent.Kind.FAILED || kind == DevEvent.Kind.NEEDS_RESTART;
        if (problem || this.engine.isEveryReloadShown()) {
            Notices.show(switch (kind) {
                case FAILED -> Notice.ofBad(title, detail);
                case NEEDS_RESTART -> Notice.ofAsk(title, detail);
                case ASSETS -> Notice.ofOn(title, detail);
                default -> Notice.ofDone(title, detail);
            });
        }
    }

    private static void add(Mod mod, DevEvent event) {
        synchronized (mod) {
            mod.events.addFirst(event);
            while (mod.events.size() > MOST_EVENTS) {
                mod.events.removeLast();
            }
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.debug("[Hotwire] {} could not be deleted", file, e);
        }
    }

    @Override
    public List<DevModView> getDevMods() {
        List<DevModView> views = new ArrayList<>();
        for (Mod mod : this.mods.values()) {
            List<DevMixin> mixins = mod.mixins;
            if (mixins == null) {
                mixins = this.engine.findMixins(mod.mod.id());
                mod.mixins = mixins;
            }
            synchronized (mod) {
                views.add(new DevModView(mod.mod.id(), mod.mod.name(), mod.state, mod.line, mod.runningNumber,
                        mod.builtAt, List.copyOf(mod.events), List.copyOf(mod.crashes.values()).reversed(), mixins,
                        mod.kept.stream().map(Kept::number).toList(), Map.copyOf(mod.parents),
                        Map.copyOf(mod.infos), findThrows(mod), Map.copyOf(mod.deltas), Map.copyOf(mod.names),
                        Set.copyOf(mod.pinned)));
            }
        }
        return views;
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void reloadNow(String id) {
        Mod mod = this.mods.get(id);
        if (mod != null) {
            this.tasks.submit("taking " + id + "'s build", () -> {
                this.take(mod);
                return null;
            });
        }
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void restartMod(String id) {
        Mod mod = this.mods.get(id);
        if (mod == null) {
            return;
        }
        this.tasks.submit("restarting " + id, () -> {
            synchronized (mod.taking) {
                long began = System.nanoTime();
                mod.state = DevState.LOADING;
                mod.line = Lang.get("hotwire.dev.restarting");
                clearCrashCard(mod);
                Restart done = this.engine.restart(id);
                if (done.failure() != null) {
                    this.fail(mod, List.of(done.failure()));
                } else {
                    mod.state = DevState.UP_TO_DATE;
                    mod.line = "";
                    List<String> lines = new ArrayList<>();
                    if (!done.leftOver().isEmpty()) {
                        lines.add(Lang.get("hotwire.dev.left_over"));
                        lines.addAll(done.leftOver());
                    }
                    this.tell(mod, DevEvent.Kind.RESTARTED, Lang.get("hotwire.dev.restarted", mod.mod.name()),
                            done.leftOver().isEmpty() ? Lang.get("hotwire.dev.took.only", BuildWords.findSeconds(began))
                                    : Lang.get("hotwire.dev.left_over.count", done.leftOver().size()), lines);
                }
            }
            return null;
        });
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void rollBack(String id, int build) {
        Mod mod = this.mods.get(id);
        if (mod == null) {
            return;
        }
        this.tasks.submit("rolling " + id + " back to build " + build, () -> {
            synchronized (mod.taking) {
                Kept to;
                synchronized (mod) {
                    to = mod.kept.stream().filter(kept -> kept.number() == build).findFirst().orElse(null);
                }
                if (to == null) {
                    return null;
                }
                DevBuild back = DevBuild.readIndex(to.jar());
                int number = to.number();
                if (back == null) {
                    this.fail(mod, List.of(Lang.get("hotwire.dev.unreadable_file", to.jar().getFileName())));
                    return null;
                }
                long began = System.nanoTime();
                mod.state = DevState.LOADING;
                mod.line = Lang.get("hotwire.dev.loading");
                clearCrashCard(mod);
                ModUpdater.Result result = this.engine.updateInPlace(back.getJar());
                if (result instanceof ModUpdater.Result.Updated) {
                    int from = mod.runningNumber;
                    this.accept(mod, back, number);
                    String title = number == 0 ? Lang.get("hotwire.dev.rolled_back.start", mod.mod.name())
                            : Lang.get("hotwire.dev.rolled_back", mod.mod.name(), number);
                    String took = Lang.get("hotwire.dev.took.only", BuildWords.findSeconds(began));
                    this.tell(mod, DevEvent.Kind.IN, title, took, List.of(Lang.get("hotwire.dev.rolled_back.from",
                            from)));
                } else {
                    this.fail(mod, List.of(this.engine.describe(result)));
                }
            }
            return null;
        });
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void merge(String id, List<Integer> builds, boolean anyway) {
        Mod mod = this.mods.get(id);
        if (mod != null) {
            this.tasks.submit("merging builds of " + id, () -> {
                synchronized (mod.taking) {
                    this.mergeNow(mod, builds, anyway);
                }
                return null;
            });
        }
    }

    private void mergeNow(Mod mod, List<Integer> chosen, boolean anyway) {
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
            mod.kept.forEach(kept -> jars.put(kept.number(), kept.jar()));
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
                merged.writeIndex();
            } catch (IOException e) {
                deleteQuietly(jar);
                deleteQuietly(jar.resolveSibling(jar.getFileName() + ".idx"));
                LOGGER.warn("[Hotwire] builds {} of {} could not be merged", plan.builds(), mod.mod.id(), e);
                this.tellMergeFailed(mod, Lang.get("hotwire.dev.merge.unwritten"));
                return;
            }
            mod.builds = number;
        }
        DevBuildInfo info = findMergedInfo(plan, number, base, merged, infos);
        synchronized (mod) {
            mod.kept.removeIf(kept -> plan.removed().contains(kept.number()));
            if (!plan.isFast()) {
                mod.kept.addFirst(new Kept(number, merged.getJar()));
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
                deleteQuietly(jar);
                deleteQuietly(jar.resolveSibling(jar.getFileName() + ".idx"));
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
        add(mod, new DevEvent(System.currentTimeMillis(), DevEvent.Kind.MERGED, title, lines, number));
        LOGGER.info("[Hotwire] {}: merged builds {} into build {}", mod.mod.id(), plan.builds(), number);
    }

    private static DevBuildInfo findMergedInfo(BuildMerge.Plan plan, int number, DevBuild base, DevBuild merged,
            Map<Integer, DevBuildInfo> infos) {
        DevBuild.Diff diff = base.diff(merged);
        Map<String, byte[]> was = base.readEntries(diff.changedClasses());
        Map<String, byte[]> after = merged.readEntries(combine(diff.changedClasses(), diff.addedClasses()));
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
        return BuildMerge.compose(before, own, number, plan.parent(), at, plan.builds(), lines);
    }

    private @Nullable DevBuild findBuild(Mod mod, int number, Map<Integer, Path> jars) {
        if (number == mod.runningNumber) {
            return mod.running;
        }
        Path jar = jars.get(number);
        if (jar == null && number == 0) {
            jar = this.folder.resolve(mod.mod.id()).resolve("build-0.jar");
        }
        return jar == null ? null : DevBuild.readIndex(jar);
    }

    private void tellMergeFailed(Mod mod, String why) {
        String title = Lang.get("hotwire.dev.merge.failed", mod.mod.name());
        add(mod, new DevEvent(System.currentTimeMillis(), DevEvent.Kind.FAILED, title, List.of(why)));
        LOGGER.info("[Hotwire] {}: {} {}", mod.mod.id(), title, why);
        Notices.show(Notice.ofBad(title, why));
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void duplicate(String id, int build) {
        Mod mod = this.mods.get(id);
        if (mod == null || build == 0) {
            return;
        }
        this.tasks.submit("copying build " + build + " of " + id, () -> {
            synchronized (mod.taking) {
                this.duplicateNow(mod, build);
            }
            return null;
        });
    }

    private void duplicateNow(Mod mod, int build) {
        Path from;
        synchronized (mod) {
            from = mod.kept.stream().filter(kept -> kept.number() == build).map(Kept::jar).findFirst().orElse(null);
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
            link(from, jar);
            link(from.resolveSibling(from.getFileName() + ".idx"), index);
        } catch (IOException e) {
            deleteQuietly(jar);
            deleteQuietly(index);
            LOGGER.warn("[Hotwire] build {} of {} could not be copied", build, mod.mod.id(), e);
            Notices.show(Notice.ofBad(Lang.get("hotwire.dev.copy.failed", build), ""));
            return;
        }
        mod.builds = number;
        synchronized (mod) {
            mod.kept.addFirst(new Kept(number, jar));
            Integer parent = mod.parents.get(build);
            if (parent != null) {
                mod.parents.put(number, parent);
            }
            DevBuildInfo info = mod.infos.get(build);
            if (info != null) {
                mod.infos.put(number, new DevBuildInfo(number, info.madeOn(), info.at(), info.classes(),
                        info.addedClasses(), info.removedClasses(), info.files(), info.notes(), info.merged()));
            }
            DevDelta delta = mod.deltas.get(build);
            if (delta != null) {
                mod.deltas.put(number, delta);
            }
            String name = mod.names.get(build);
            mod.names.put(number, Lang.get("hotwire.dev.copy.name", name == null
                    ? Lang.get("hotwire.dev.pick.build", build) : name));
        }
        add(mod, new DevEvent(System.currentTimeMillis(), DevEvent.Kind.MERGED,
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

    @Override
    public void pin(String id, int build, boolean pinned) {
        Mod mod = this.mods.get(id);
        if (mod != null) {
            synchronized (mod) {
                if (pinned) {
                    mod.pinned.add(build);
                } else {
                    mod.pinned.remove(build);
                }
            }
        }
    }

    @Override
    public void rename(String id, int build, String name) {
        Mod mod = this.mods.get(id);
        if (mod != null) {
            String plain = name.strip();
            synchronized (mod) {
                if (plain.isEmpty()) {
                    mod.names.remove(build);
                } else {
                    mod.names.put(build, plain.length() > NAME_MOST ? plain.substring(0, NAME_MOST) : plain);
                }
            }
        }
    }

    @Override
    public void openSource(String id, DevCrash crash) {
        if (!crash.source().isEmpty()) {
            this.openPlace(id, crash.source(), crash.line(), crash.where());
        }
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void openPlace(String id, String type, int line, String label) {
        Mod mod = this.mods.get(id);
        if (mod == null) {
            return;
        }
        this.tasks.submit("opening " + label, () -> {
            SourceOpener.open(mod.mod, type, line, label);
            return null;
        });
    }

    @Override
    public boolean isWatching() {
        return this.watching;
    }

    @Override
    public void setWatching(boolean watching) {
        this.watching = watching;
        if (watching) {
            for (Mod mod : this.mods.values()) {
                if (mod.state == DevState.WAITING) {
                    this.reloadNow(mod.mod.id());
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        current = null;
        DevLink.set(null);
        this.watch.close();
    }

    public static void watchLeftWorld(Object world) {
        DevSession session = current;
        if (session != null) {
            WorldLeaks.watch(world, session::findLoadedClasses, session::onLeak);
        }
    }

    private Map<String, List<Class<?>>> findLoadedClasses() {
        Map<String, List<Class<?>>> loaded = new LinkedHashMap<>();
        for (Mod mod : this.mods.values()) {
            Set<String> names = mod.running.getClassNames();
            loaded.put(mod.mod.id(), this.engine.findLoaded(names));
        }
        return loaded;
    }

    private void onLeak(WorldLeaks.Holder holder) {
        Mod mod = this.mods.get(holder.mod());
        if (mod == null) {
            return;
        }
        String title = Lang.get("hotwire.dev.leak", mod.mod.name());
        add(mod, new DevEvent(System.currentTimeMillis(), DevEvent.Kind.LEAK, title,
                List.of(holder.path(), Lang.get("hotwire.dev.leak.fix"))));
        Notices.show(Notice.ofBad(title, holder.path()));
    }

    public static SortedSet<String> findIds() {
        DevSession session = current;
        return session == null ? new TreeSet<>() : new TreeSet<>(session.mods.keySet());
    }
}
