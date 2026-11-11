package dev.moon.hotwire.fabric.dev;

import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.config.AtomicFiles;
import dev.moon.hotwire.dev.BuildNotes;
import dev.moon.hotwire.dev.BuildSources;
import dev.moon.hotwire.dev.DevBridge;
import dev.moon.hotwire.dev.DevBuild;
import dev.moon.hotwire.dev.DevBuildInfo;
import dev.moon.hotwire.dev.DevClassChange;
import dev.moon.hotwire.dev.DevCompare;
import dev.moon.hotwire.dev.DevCrash;
import dev.moon.hotwire.dev.DevEvent;
import dev.moon.hotwire.dev.DevExtras;
import dev.moon.hotwire.dev.DevHost;
import dev.moon.hotwire.dev.DevLink;
import dev.moon.hotwire.dev.DevMixin;
import dev.moon.hotwire.dev.DevModView;
import dev.moon.hotwire.dev.DevState;
import dev.moon.hotwire.dev.IdeOpener;
import dev.moon.hotwire.dev.LineDiff;
import dev.moon.hotwire.dev.WorldLeaks;
import dev.moon.hotwire.fabric.OpenKey;
import dev.moon.hotwire.fabric.rebuild.GameClasses;
import dev.moon.hotwire.fabric.screen.WebLinks;
import dev.moon.hotwire.fabric.update.ModUpdater;
import dev.moon.hotwire.redefine.ClassRebuilder;
import dev.moon.hotwire.ui.BuildMark;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.Notice;
import dev.moon.hotwire.ui.Notices;
import dev.moon.hotwire.ui.UpdateMethod;
import dev.moon.hotwire.ui.UpdatePreview;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DevSession implements DevHost, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DevSession.class);

    private static final int METHOD_LINES = 4;

    private static final long REMOVAL_WAIT_MILLIS = 2000;

    private static final long HALF_WAIT_MILLIS = 300;
    private static final int HALF_TRIES = 10;
    private static volatile @Nullable DevSession current;

    private static final Map<String, Start> STARTS = new ConcurrentHashMap<>();

    private record Start(DevMod mod, DevBuild build, int number, SavedHistory.@Nullable State history,
            @Nullable DevBuild before, @Nullable BuildSources sources) {
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

        boolean isHistorySaved();

        int getSavedBuilds();

        DevExtras getExtras();

        List<Class<?>> findLoaded(Set<String> names);

        boolean isInitialized(Class<?> type);

        List<DevMixin> findMixins(String id);

        List<String> redefineElsewhere(Map<String, byte[]> classes);
    }

    private static final int NAME_MOST = 40;

    //? if >=26.1 {
    private static final boolean DEV_NAMES_ONLY = false;
    //?} else {
    /*private static final boolean DEV_NAMES_ONLY = true;
    *///?}

    private static final long MOST_ROOM_NEEDED = 64L * 1024 * 1024;

    final Map<String, SessionMod> mods = new LinkedHashMap<>();
    private final Path folder;
    private final Tasks tasks;
    final Engine engine;
    private final DevWatch watch;
    private final DevBuildEdits edits;
    private final DevCompares compares;
    private final ClearedBuilds cleared;
    private final ProjectBranches branches;
    private volatile boolean watching = true;

    private final Map<Path, Set<String>> changedAtStart = new ConcurrentHashMap<>();

    private DevSession(Path folder, Tasks tasks, Engine engine) throws IOException {
        this.folder = folder;
        this.tasks = tasks;
        this.engine = engine;
        this.edits = new DevBuildEdits(folder, engine);
        this.compares = new DevCompares(tasks, this::putIn, engine::findLoaded);
        this.branches = new ProjectBranches(tasks);
        this.cleared = new ClearedBuilds(folder, tasks, this::saveHistory);
        List<DevMod> watched = new ArrayList<>();
        for (Start start : STARTS.values()) {
            SessionMod mod = new SessionMod(start.mod(), start.build(), folder, start.sources());
            mod.runningNumber = start.number();
            mod.codeNumber = start.number();
            mod.builds = start.number();
            this.cleared.delete(mod);
            if (start.sources() == null) {
                this.takeSources(mod, start.number());
            }
            if (start.history() != null) {
                this.restore(mod, start, start.history());
            }
            this.mods.put(start.mod().id(), mod);
            DevBuildEdits.collectStore(folder.resolve(start.mod().id()));
            watched.add(start.mod());
        }
        this.watch = new DevWatch(watched, this::onBuilt);
        this.readChangedAtStart();
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void readChangedAtStart() {
        Set<Path> projects = new HashSet<>();
        this.mods.values().forEach(mod -> projects.addAll(this.findProjects(mod)));
        for (Path project : projects) {
            this.tasks.submit("reading git for " + project.getFileName(), () -> {
                Set<String> changed = SourceGit.readChanged(project);
                if (changed != null) {
                    this.changedAtStart.put(project, changed);
                }
                return null;
            });
        }
    }

    public static Path takeStart(DevMod mod, Path folder, boolean withHistory) throws IOException {
        Path dir = folder.resolve(mod.id());
        SavedHistory.State history = withHistory ? SavedHistory.read(dir) : null;
        Path first = dir.resolve("build-0.jar");
        if (history == null) {
            SavedHistory.forget(dir);
            deleteBuildsBut(first, first.resolveSibling(first.getFileName() + ".idx"));
            DevBuild before = DevBuild.readIndex(first);
            DevBuild build = DevBuild.take(mod.folders(), first, before);
            STARTS.put(mod.id(), new Start(mod, build, 0, null, null, StartSources.adopt(mod, folder, 0, build)));
            return build.getJar();
        }
        SavedHistory.Build last = Objects.requireNonNull(history.find(history.running()));
        DevBuild before = DevBuild.readIndex(dir.resolve(last.jar()));
        int next = history.last() + 1;
        DevBuild build = DevBuild.take(mod.folders(), dir.resolve("build-" + next + ".jar"), before);
        if (before != null && build != before && before.diff(build).isEmpty()) {
            AtomicFiles.moveInto(build.getJar(), before.getJar());
            AtomicFiles.moveInto(build.getJar().resolveSibling(build.getJar().getFileName() + ".idx"),
                    before.getJar().resolveSibling(before.getJar().getFileName() + ".idx"));
            build = Objects.requireNonNull(DevBuild.readIndex(before.getJar()));
            before = build;
        }

        List<Path> staying = new ArrayList<>(List.of(first, build.getJar()));
        history.builds().forEach(one -> staying.add(dir.resolve(one.jar())));
        List<Path> indexed = new ArrayList<>(staying);
        indexed.forEach(jar -> staying.add(jar.resolveSibling(jar.getFileName() + ".idx")));
        deleteBuildsBut(staying.toArray(Path[]::new));
        int number = build == before ? history.running() : next;
        STARTS.put(mod.id(), new Start(mod, build, number, history, build == before ? null : before,
                StartSources.adopt(mod, folder, number, build)));
        LOGGER.info("[Hotwire] {} starts on build {}, with the builds the last session saved", mod.id(),
                build == before ? history.running() : next);
        return build.getJar();
    }

    public static void takeEarlySources(Path folder) {
        StartSources.takeEarly(folder);
    }

    private void restore(SessionMod mod, Start start, SavedHistory.State history) {
        Path dir = this.folder.resolve(mod.mod.id());
        synchronized (mod) {
            mod.builds = Math.max(history.last(), start.number());
            for (SavedHistory.Build build : history.builds()) {
                if (build.parent() >= 0) {
                    mod.parents.put(build.number(), build.parent());
                }
                if (build.name() != null) {
                    mod.names.put(build.number(), build.name());
                }
                if (build.pinned()) {
                    mod.pinned.add(build.number());
                }
                if (build.info() != null) {
                    mod.infos.put(build.number(), build.info());
                }
                if (build.delta() != null) {
                    mod.deltas.put(build.number(), build.delta());
                }
                Path jar = dir.resolve(build.jar());
                DevBuild saved = build.number() == start.number() ? null : DevBuild.readIndex(jar);
                if (saved != null) {
                    if (!jar.equals(mod.startJar) && !jar.getFileName().toString().equals("build-0.jar")) {
                        saved.dropJar();
                    }
                    mod.saved.addLast(new SavedBuild(build.number(), jar));
                }
            }
        }
        DevBuild before = start.before();
        if (before != null) {
            DevBuild.Diff diff = before.diff(start.build());
            synchronized (mod) {
                mod.parents.put(start.number(), history.running());
            }
            this.edits.record(mod, start.build(), start.number(), history.running(), diff,
                    before.readEntries(diff.changedClasses()),
                    start.build().readEntries(combine(diff.changedClasses(), diff.addedClasses())), List.of());
        }
        this.edits.dropPastLimit(mod);
    }

    private static OptionalLong findRoom(Path folder) {
        try {
            return OptionalLong.of(Files.getFileStore(folder).getUsableSpace());
        } catch (IOException e) {
            return OptionalLong.empty();
        }
    }

    private static long findSize(Path jar) {
        try {
            return Files.size(jar);
        } catch (IOException e) {
            return 0;
        }
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void noteDiffs(SessionMod mod, int number, Map<String, byte[]> was) {
        List<Path> projects = this.findProjects(mod);
        if (projects.isEmpty()) {
            return;
        }
        this.tasks.submit("reading the changed sources of build " + number + " of " + mod.mod.id(), () -> {
            DevBuildInfo info;
            synchronized (mod) {
                info = mod.infos.get(number);
            }
            if (info == null) {
                return null;
            }
            List<DevClassChange> found = new ArrayList<>();
            for (DevClassChange change : info.classes()) {
                byte[] before = was.get(change.type().replace('.', '/') + ".class");
                found.add(this.findDiffs(mod, number, projects, change, before));
            }
            synchronized (mod) {
                mod.infos.computeIfPresent(number, (build, now) -> now.withClasses(found));
            }
            this.saveHistory(mod);
            return null;
        });
    }

    private DevClassChange findDiffs(SessionMod mod, int number, List<Path> projects, DevClassChange change,
            byte @Nullable [] before) {
        Path file;
        List<String> now;
        try {
            file = IdeOpener.findSource(projects, change.type());
            if (file == null) {
                return change;
            }
            List<String> saved = readSaved(mod.buildSources, number, file);
            now = saved != null ? saved : Files.readAllLines(file);
        } catch (IOException | RuntimeException e) {
            return change;
        }
        Integer parent;
        List<String> old;
        synchronized (mod) {
            parent = mod.parents.get(number);
            old = mod.sources.put(file, now);
        }
        List<String> savedOld = parent == null ? null : readSaved(mod.buildSources, parent, file);
        if (savedOld != null) {
            old = savedOld;
        } else if (old == null) {
            old = this.readCommittedIfClean(projects, file);
        }
        Map<String, List<Integer>> oldLines = BuildNotes.findMethodLines(before);
        Map<String, List<String>> diffs = new HashMap<>();
        for (String method : change.changed()) {
            List<Integer> to = change.lines().get(method);
            List<Integer> from = oldLines.get(method);
            if (old != null && to != null && from != null) {
                List<String> lines = LineDiff.diff(slice(old, from), slice(now, to));
                if (!lines.isEmpty()) {
                    diffs.put(method, lines);
                }
            }
        }
        return diffs.isEmpty() ? change : change.withDiffs(diffs);
    }

    @Override
    public @Nullable String findCode(String id, int build, String type, String method, boolean before) {
        SessionMod mod = this.mods.get(id);
        if (mod == null) {
            return null;
        }
        try {
            Path file = IdeOpener.findSource(this.findProjects(mod), type);
            Integer parent;
            DevBuildInfo info;
            synchronized (mod) {
                parent = mod.parents.get(build);
                info = mod.infos.get(build);
            }
            int at = before ? parent == null ? -1 : parent : build;
            List<String> lines = file == null || at < 0 ? null : readSaved(mod.buildSources, at, file);
            if (lines == null || info == null) {
                return null;
            }
            List<Integer> span = null;
            if (before) {
                Path jar = this.findJar(mod, at);
                DevBuild old = jar == null ? null : DevBuild.readIndex(jar);
                String entry = type.replace('.', '/') + ".class";
                span = old == null ? null : BuildNotes.findMethodLines(old.readEntries(List.of(entry)).get(entry))
                        .get(method);
            } else {
                for (DevClassChange change : info.classes()) {
                    if (change.type().equals(type)) {
                        span = change.lines().get(method);
                    }
                }
            }
            return span == null ? null : String.join("\n", slice(lines, span));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public void clear(String id, List<Integer> builds) {
        SessionMod mod = this.mods.get(id);
        if (mod != null) {
            this.cleared.clear(mod, builds);
        }
    }

    @Override
    public void restoreCleared(String id) {
        SessionMod mod = this.mods.get(id);
        if (mod != null) {
            this.cleared.restore(mod);
        }
    }

    @Override
    public void startCompare(String id, int asked, int b) {
        SessionMod mod = this.mods.get(id);
        if (mod != null) {
            this.compares.start(mod, b);
        }
    }

    @Override
    public void switchCompare(String id) {
        SessionMod mod = this.mods.get(id);
        if (mod != null) {
            this.compares.flip(mod);
        }
    }

    @Override
    public void stopCompare(String id) {
        SessionMod mod = this.mods.get(id);
        if (mod != null) {
            this.compares.stop(mod);
        }
    }

    public @Nullable DevCompare findRunningCompare() {
        return DevCompares.findRunning(this.mods.values());
    }

    @Override
    public String findBranch(String id) {
        SessionMod mod = this.mods.get(id);
        return this.branches.find(id, mod == null ? List.of() : mod.mod.folders());
    }

    @Override
    public String getCompareKey() {
        return OpenKey.describeFlipKey();
    }

    @Override
    public void setCompareKey(int key) {
        OpenKey.setFlipKey(key);
    }

    private @Nullable Path findJar(SessionMod mod, int build) {
        if (build == mod.runningNumber) {
            return mod.running.getJar();
        }
        synchronized (mod) {
            return mod.saved.stream().filter(saved -> saved.number() == build).map(SavedBuild::jar).findFirst()
                    .orElse(null);
        }
    }

    @Override
    public DevExtras getExtras() {
        return this.engine.getExtras();
    }

    @Override
    public void copy(String text, String what) {
        Minecraft game = Minecraft.getInstance();
        game.execute(() -> game.keyboardHandler.setClipboard(text));
        Notices.show(Notice.ofOn(Lang.get("hotwire.dev.copied.code"), what));
    }

    private static @Nullable List<String> readSaved(@Nullable BuildSources sources, int build, Path file) {
        if (sources == null || !file.toAbsolutePath().normalize().startsWith(sources.getProject())) {
            return null;
        }
        try {
            String path = sources.getProject().relativize(file.toAbsolutePath().normalize()).toString()
                    .replace('\\', '/');
            BuildSources.Entry entry = sources.read(build).get(path);
            return entry == null ? null : new String(sources.readContents(entry.hash()),
                    StandardCharsets.UTF_8).lines().toList();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private @Nullable List<String> readCommittedIfClean(List<Path> projects, Path file) {
        for (Path project : projects) {
            if (!file.startsWith(project)) {
                continue;
            }
            String relative = project.relativize(file).toString().replace('\\', '/');
            Set<String> changed = this.changedAtStart.get(project);
            return changed == null || changed.contains(relative) ? null : SourceGit.readCommitted(project, relative);
        }
        return null;
    }

    private static List<String> slice(List<String> lines, List<Integer> span) {
        int from = Math.max(0, span.get(0) - 1);
        int to = Math.min(lines.size(), span.get(1));
        return from >= to ? List.of() : lines.subList(from, to);
    }

    private List<Path> findProjects(SessionMod mod) {
        List<Path> projects = new ArrayList<>();
        for (Path folder : mod.mod.folders()) {
            Path project = DevMod.findProject(folder);
            if (project != null && !projects.contains(project)) {
                projects.add(project);
            }
        }
        return projects;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void noteGit(SessionMod mod, int number) {
        List<Path> folders = mod.mod.folders();
        if (folders.isEmpty()) {
            return;
        }
        this.tasks.submit("reading git for build " + number + " of " + mod.mod.id(), () -> {
            SourceGit.State git = SourceGit.read(DevMod.findProject(folders.getFirst()));
            if (git != null) {
                synchronized (mod) {
                    mod.infos.computeIfPresent(number, (build, info) -> info.withGit(git.at(), git.uncommitted()));
                }
            }
            return null;
        });
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    void saveHistory(SessionMod mod) {
        if (!this.engine.isHistorySaved()) {
            return;
        }
        this.tasks.submit("keeping the builds of " + mod.mod.id(), () -> {
            DevBuild running = mod.running;
            Path index = running.getJar().resolveSibling(running.getJar().getFileName() + ".idx");
            try {
                if (!Files.isRegularFile(index)) {
                    running.writeIndex();
                }
            } catch (IOException e) {
                LOGGER.debug("[Hotwire] {} could not be indexed", running.getJar(), e);
            }
            SavedHistory.State state;
            synchronized (mod) {
                state = SavedHistory.capture(mod);
            }
            SavedHistory.write(this.folder.resolve(mod.mod.id()), state);
            return null;
        });
    }

    static void deleteBuildsBut(Path... staying) {
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

    public static void onCrash(String id, Throwable thrown, String what, String where, String source, int line) {
        DevSession session = current;
        if (session != null) {
            DevCrashes.onCrash(session, id, thrown, what, where, source, line);
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
        for (SessionMod mod : session.mods.values()) {
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

    static boolean isFullRedefinition() {
        return fullRedefinition;
    }

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
        for (SessionMod mod : this.mods.values()) {
            if (mod.running.hasClass(binary)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void onBuilt(DevMod built) {
        SessionMod mod = this.mods.get(built.id());
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

    private void take(SessionMod mod) {
        synchronized (mod.taking) {
            boolean again = false;
            try {
                long start = System.nanoTime();
                long first = this.watch.findFirstChange(mod.mod);
                int number = mod.builds + 1;
                Path jar = this.folder.resolve(mod.mod.id()).resolve("build-" + number + ".jar");

                OptionalLong room = findRoom(jar.getParent());
                long needs = Math.max(MOST_ROOM_NEEDED, 2 * findSize(mod.running.getJar()));
                if (room.isPresent() && room.getAsLong() < needs) {
                    this.fail(mod, List.of(Lang.get("hotwire.dev.no_room", room.getAsLong() / (1024 * 1024))));
                    return;
                }
                DevBuild built;
                try {
                    built = DevBuild.take(mod.mod.folders(), jar, mod.running);
                } catch (IOException e) {
                    dropUnused(jar);
                    if (mod.halfTries++ < HALF_TRIES) {
                        again = true;
                        this.watch.lookAgain(mod.mod, TimeUnit.MILLISECONDS.toNanos(HALF_WAIT_MILLIS));
                        return;
                    }
                    mod.halfTries = 0;

                    String file = e instanceof FileSystemException locked && locked.getFile() != null
                            ? String.valueOf(Path.of(locked.getFile()).getFileName()) : String.valueOf(e.getMessage());
                    this.fail(mod, List.of(Lang.get("hotwire.dev.unreadable_file", file)));
                    return;
                }
                long jarDone = System.nanoTime();

                if (built == mod.running) {
                    if (mod.state == DevState.WAITING) {
                        mod.state = DevState.UP_TO_DATE;
                    }
                    return;
                }

                DevBuild inFolders = mod.inFolders;
                if (inFolders != null && inFolders.diff(built).isEmpty()) {
                    dropUnused(jar);
                    if (mod.state == DevState.WAITING) {
                        mod.state = DevState.UP_TO_DATE;
                    }
                    return;
                }
                DevBuild.Diff diff = mod.running.diff(built);
                long now = System.currentTimeMillis();

                boolean empty = built.getClassNames().isEmpty();
                boolean compiling = this.watch.usesMark(mod.mod) && !this.watch.isMarked(mod.mod);
                if (diff.removedClasses().isEmpty() && !empty) {
                    mod.goneSince = 0;
                } else if (empty || compiling || mod.goneSince == 0 || now - mod.goneSince < REMOVAL_WAIT_MILLIS) {
                    if (mod.goneSince == 0) {
                        mod.goneSince = now;
                    }
                    dropUnused(jar);
                    again = true;
                    this.watch.lookAgain(mod.mod, TimeUnit.MILLISECONDS.toNanos(REMOVAL_WAIT_MILLIS));
                    return;
                }
                if (diff.isEmpty()) {
                    dropUnused(jar);
                    if (mod.state == DevState.WAITING) {
                        mod.state = DevState.UP_TO_DATE;
                    }
                    return;
                }

                Map<String, byte[]> classes = built.readEntries(combine(diff.changedClasses(), diff.addedClasses()));
                String half = classes.entrySet().stream().filter(entry -> !BuildNotes.isWhole(entry.getValue()))
                        .map(Map.Entry::getKey).findFirst().orElse(null);
                if (half != null && mod.halfTries++ < HALF_TRIES) {
                    dropUnused(jar);
                    again = true;
                    this.watch.lookAgain(mod.mod, TimeUnit.MILLISECONDS.toNanos(HALF_WAIT_MILLIS));
                    return;
                }
                mod.halfTries = 0;
                if (half != null) {
                    dropUnused(jar);
                    this.fail(mod, List.of(Lang.get("hotwire.dev.half", half)));
                    return;
                }
                long readDone = System.nanoTime();
                this.takeSources(mod, number);
                long sourcesDone = System.nanoTime();
                DevBuild before = mod.running;

                Map<String, byte[]> was = before.readEntries(diff.changedClasses());
                mod.builds = number;
                mod.state = DevState.LOADING;
                mod.line = Lang.get("hotwire.dev.loading");
                long began = System.nanoTime();
                UpdatePreview preview = this.engine.preview(jar);
                if (preview instanceof UpdatePreview.Ready ready && ready.method() == UpdateMethod.IN_PLACE) {
                    DevCrashes.clearCrashCard(mod);
                    long planDone = System.nanoTime();
                    ModUpdater.Result result = this.engine.updateInPlace(jar);
                    long updateDone = System.nanoTime();
                    if (result instanceof ModUpdater.Result.Updated) {
                        int madeOn = mod.runningNumber;
                        mod.codeNumber = number;
                        mod.inFolders = null;
                        this.edits.accept(mod, built, number);
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
                            List<String> notes = this.edits.findNotes(mod, diff, was, classes);
                            lines.addAll(notes);
                            String took = Lang.get("hotwire.dev.took", BuildWords.describeClasses(diff),
                                    BuildWords.findSeconds(began));
                            String detail = notes.isEmpty() ? took : notes.getFirst();

                            lines.add(Lang.get("hotwire.dev.build.made_on", madeOn));
                            this.edits.record(mod, built, number, madeOn, diff, was, classes, notes);
                            this.noteDiffs(mod, number, was);
                            this.tell(mod, DevEvent.Kind.IN, Lang.get("hotwire.dev.in.build", mod.mod.name(), number),
                                    detail, lines);
                        } else {
                            String took = Lang.get("hotwire.dev.took", BuildWords.describeFiles(diff),
                                    BuildWords.findSeconds(began));
                            this.tell(mod, DevEvent.Kind.ASSETS, Lang.get("hotwire.dev.assets.build", mod.mod.name(),
                                    number), took, List.of(Lang.get("hotwire.dev.changed",
                                    String.join(", ", BuildWords.list(diff.changedFiles()))),
                                    Lang.get("hotwire.dev.build.made_on", madeOn)));
                            this.edits.record(mod, built, number, madeOn, diff, Map.of(), Map.of(), List.of());
                        }
                        this.noteGit(mod, number);
                        this.saveHistory(mod);
                        long end = System.nanoTime();
                        this.watch.forgetFirstChange(mod.mod);
                        LOGGER.info("[Hotwire] build {} of {} in {} ms: waited {} ms, jar {} ms, read {} ms, "
                                + "sources {} ms, plan {} ms, update {} ms, report {} ms", number, mod.mod.id(),
                                first == 0 ? -1 : (updateDone - first) / 1_000_000,
                                first == 0 ? -1 : (start - first) / 1_000_000,
                                (jarDone - start) / 1_000_000, (readDone - jarDone) / 1_000_000,
                                (sourcesDone - readDone) / 1_000_000, (planDone - sourcesDone) / 1_000_000,
                                (updateDone - planDone) / 1_000_000, (end - updateDone) / 1_000_000);
                    } else {
                        mod.codeNumber = -1;
                        this.fail(mod, List.of(this.engine.describe(result)));
                    }
                } else if (preview instanceof UpdatePreview.Ready ready) {
                    mod.codeNumber = -1;
                    mod.state = DevState.NEEDS_RESTART;
                    mod.line = Lang.get("hotwire.dev.needs_restart.line");
                    this.tell(mod, DevEvent.Kind.NEEDS_RESTART, Lang.get("hotwire.dev.needs_restart", mod.mod.name()),
                            ready.lines().isEmpty() ? "" : ready.lines().getFirst(), ready.lines());
                } else {
                    mod.codeNumber = -1;
                    this.fail(mod, List.of(BuildWords.describePreview(preview)));
                }
            } finally {
                if (!again) {
                    this.watch.forgetFirstChange(mod.mod);
                }
            }
        }
    }

    static SortedSet<String> combine(SortedSet<String> a, SortedSet<String> b) {
        SortedSet<String> both = new TreeSet<>(a);
        both.addAll(b);
        return both;
    }

    private void fail(SessionMod mod, List<String> why) {
        mod.state = DevState.FAILED;
        mod.line = why.isEmpty() ? "" : why.getFirst();
        this.tell(mod, DevEvent.Kind.FAILED, Lang.get("hotwire.dev.failed", mod.mod.name()), mod.line, why);
    }

    private void tell(SessionMod mod, DevEvent.Kind kind, String title, String detail, List<String> lines) {
        boolean in = kind == DevEvent.Kind.IN || kind == DevEvent.Kind.ASSETS;
        mod.addEvent(new DevEvent(System.currentTimeMillis(), kind, title, lines, in ? mod.runningNumber : -1));
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

    private static void dropUnused(Path jar) {
        deleteQuietly(jar);
        deleteQuietly(jar.resolveSibling(jar.getFileName() + ".idx"));
    }

    static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.debug("[Hotwire] {} could not be deleted", file, e);
        }
    }

    @Override
    public List<DevModView> getDevMods() {
        List<DevModView> views = new ArrayList<>();
        for (SessionMod mod : this.mods.values()) {
            List<DevMixin> mixins = mod.mixins;
            if (mixins == null) {
                mixins = this.engine.findMixins(mod.mod.id());
                mod.mixins = mixins;
            }
            synchronized (mod) {
                views.add(new DevModView(mod.mod.id(), mod.mod.name(), mod.state, mod.line, mod.runningNumber,
                        mod.builtAt, List.copyOf(mod.events), List.copyOf(mod.crashes.values()).reversed(), mixins,
                        mod.saved.stream().map(SavedBuild::number).toList(), Map.copyOf(mod.parents),
                        Map.copyOf(mod.infos), DevCrashes.findThrows(mod), Map.copyOf(mod.deltas),
                        Map.copyOf(mod.names), Set.copyOf(mod.pinned), mod.codeNumber, !mod.savePoints.isEmpty(),
                        mod.findSizes(this.tasks),
                        mod.cleared.stream().map(cleared -> cleared.build().number()).toList(),
                        DevCompares.find(mod)));
            }
        }
        return views;
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void reloadNow(String id) {
        SessionMod mod = this.mods.get(id);
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
        SessionMod mod = this.mods.get(id);
        if (mod == null) {
            return;
        }
        this.tasks.submit("restarting " + id, () -> {
            synchronized (mod.taking) {
                long began = System.nanoTime();
                mod.state = DevState.LOADING;
                mod.line = Lang.get("hotwire.dev.restarting");
                DevCrashes.clearCrashCard(mod);
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

    public void stepBack() {
        for (SessionMod mod : this.mods.values()) {
            Integer parent;
            synchronized (mod) {
                Integer madeOn = mod.parents.get(mod.runningNumber);
                parent = madeOn != null && mod.saved.stream().anyMatch(saved -> saved.number() == madeOn) ? madeOn
                        : null;
            }
            if (parent == null) {
                Notices.show(Notice.ofAsk(Lang.get("hotwire.dev.back.none", mod.mod.name()),
                        Lang.get("hotwire.dev.back.none.line")));
            } else {
                this.revertTo(mod.mod.id(), parent);
            }
        }
    }

    public void flip() {
        for (SessionMod mod : this.mods.values()) {
            if (mod.comparing != null) {
                this.switchCompare(mod.mod.id());
                continue;
            }
            int last = mod.pairA >= 0 && mod.runningNumber == mod.pairA ? mod.pairB
                    : mod.pairA >= 0 && mod.runningNumber == mod.pairB ? mod.pairA : mod.lastRunning;
            boolean saved;
            synchronized (mod) {
                saved = mod.saved.stream().anyMatch(build -> build.number() == last);
            }
            if (saved) {
                this.revertTo(mod.mod.id(), last);
            } else {
                Notices.show(Notice.ofAsk(Lang.get("hotwire.dev.back.none", mod.mod.name()),
                        Lang.get("hotwire.dev.flip.none.line")));
            }
        }
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void revertTo(String id, int build) {
        SessionMod mod = this.mods.get(id);
        if (mod == null) {
            return;
        }
        this.tasks.submit("rolling " + id + " back to build " + build, () -> {
            synchronized (mod.taking) {
                this.putIn(mod, build);
            }
            return null;
        });
    }

    private boolean putIn(SessionMod mod, int build) {
        SavedBuild to;
        synchronized (mod) {
            to = mod.saved.stream().filter(saved -> saved.number() == build).findFirst().orElse(null);
        }
        if (to == null) {
            this.fail(mod, List.of(Lang.get("hotwire.dev.not_kept", build)));
            return false;
        }
        DevBuild back = DevBuild.readIndex(to.jar());
        int number = to.number();
        if (back != null && !back.isIntact()) {
            this.fail(mod, List.of(Lang.get("hotwire.dev.changed_on_disk", number)));
            return false;
        }
        if (back == null) {
            this.fail(mod, List.of(Lang.get("hotwire.dev.unreadable_file", to.jar().getFileName())));
            return false;
        }
        long began = System.nanoTime();
        mod.state = DevState.LOADING;
        mod.line = Lang.get("hotwire.dev.loading");
        DevCrashes.clearCrashCard(mod);
        Path backJar;
        try {
            backJar = back.ensureJar();
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] build {} of {} could not be read back from its files", number, mod.mod.id(), e);
            this.fail(mod, List.of(Lang.get("hotwire.dev.changed_on_disk", number)));
            return false;
        }
        ModUpdater.Result result = this.engine.updateInPlace(backJar);
        if (result instanceof ModUpdater.Result.Updated) {
            int from = mod.runningNumber;

            if (mod.inFolders == null) {
                mod.inFolders = mod.running;
            }
            this.edits.accept(mod, back, number);
            String title = number == 0 ? Lang.get("hotwire.dev.rolled_back.start", mod.mod.name())
                    : Lang.get("hotwire.dev.rolled_back", mod.mod.name(), number);
            String took = Lang.get("hotwire.dev.took.only", BuildWords.findSeconds(began));
            this.tell(mod, DevEvent.Kind.IN, title, took, List.of(Lang.get("hotwire.dev.rolled_back.from",
                    from)));
            this.saveHistory(mod);
            return true;
        }
        this.fail(mod, List.of(this.engine.describe(result)));
        return false;
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void restore(String id, int build) {
        SessionMod mod = this.mods.get(id);
        if (mod == null) {
            return;
        }
        this.tasks.submit("restoring build " + build + " of " + id, () -> {
            synchronized (mod.taking) {
                BuildSources sources = mod.buildSources;
                try {
                    if (sources == null || sources.read(build).isEmpty()) {
                        this.fail(mod, List.of(Lang.get("hotwire.dev.restore.no_sources", build)));
                        return null;
                    }
                    String point = "before-restore-" + System.currentTimeMillis();
                    sources.takeAs(point);
                    String code = String.valueOf(mod.codeNumber);
                    if (mod.codeNumber >= 0 && sources.isSame(point, code)) {
                        sources.delete(point);
                        point = code;
                    }

                    if (!point.equals(String.valueOf(build))) {
                        mod.savePoints.push(point);
                    }
                    List<String> written = sources.restore(String.valueOf(build));
                    Set<String> notSeen = sources.readStale(String.valueOf(build));
                    mod.codeNumber = build;
                    if (build != mod.runningNumber && !this.putIn(mod, build)) {
                        return null;
                    }
                    List<String> lines = new ArrayList<>();
                    lines.add(Lang.get("hotwire.dev.restored.undo"));
                    for (String path : notSeen) {
                        lines.add(Lang.get("hotwire.dev.restored.unseen", path.substring(path.lastIndexOf('/') + 1)));
                    }
                    this.tell(mod, DevEvent.Kind.IN, Lang.get("hotwire.dev.restored", mod.mod.name(), build),
                            Lang.get("hotwire.dev.restored.files", written.size()), lines);
                    this.openFirstSource(sources, written);
                } catch (IOException | RuntimeException e) {
                    LOGGER.warn("[Hotwire] build {} of {} could not be restored", build, id, e);
                    this.fail(mod, List.of(Lang.get("hotwire.dev.restore.failed", String.valueOf(e.getMessage()))));
                }
            }
            return null;
        });
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void restoreSavePoint(String id) {
        SessionMod mod = this.mods.get(id);
        if (mod == null || mod.savePoints.isEmpty()) {
            return;
        }
        this.tasks.submit("undoing the restore of " + id, () -> {
            synchronized (mod.taking) {
                BuildSources sources = mod.buildSources;
                String point = mod.savePoints.peek();
                if (sources == null || point == null) {
                    return null;
                }
                try {
                    List<String> written = sources.restore(point);
                    mod.savePoints.remove(point);
                    boolean isBuild = point.chars().allMatch(Character::isDigit);
                    mod.codeNumber = isBuild ? Integer.parseInt(point) : -1;
                    if (!isBuild) {
                        sources.delete(point);
                    } else if (mod.codeNumber != mod.runningNumber && !this.putIn(mod, mod.codeNumber)) {
                        return null;
                    }
                    this.tell(mod, DevEvent.Kind.IN, Lang.get("hotwire.dev.undone", mod.mod.name()),
                            Lang.get("hotwire.dev.restored.files", written.size()),
                            isBuild ? List.of() : List.of(Lang.get("hotwire.dev.undone.build")));
                    this.openFirstSource(sources, written);
                } catch (IOException | RuntimeException e) {
                    LOGGER.warn("[Hotwire] the restore of {} could not be undone", id, e);
                    this.fail(mod, List.of(Lang.get("hotwire.dev.restore.failed", String.valueOf(e.getMessage()))));
                }
            }
            return null;
        });
    }

    private void openFirstSource(BuildSources sources, List<String> written) {
        for (String path : written) {
            Path file = sources.getProject().resolve(path);
            if ((path.endsWith(".java") || path.endsWith(".kt")) && Files.isRegularFile(file)) {
                IdeOpener.open(file, 1);
                return;
            }
        }
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void merge(String id, List<Integer> builds, boolean anyway) {
        SessionMod mod = this.mods.get(id);
        if (mod != null) {
            this.tasks.submit("merging builds of " + id, () -> {
                synchronized (mod.taking) {
                    this.edits.mergeNow(mod, builds, anyway);
                }
                this.saveHistory(mod);
                return null;
            });
        }
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void copyBuild(String id, int build) {
        SessionMod mod = this.mods.get(id);
        if (mod == null || build == 0) {
            return;
        }
        this.tasks.submit("copying build " + build + " of " + id, () -> {
            synchronized (mod.taking) {
                this.edits.copyNow(mod, build);
            }
            this.saveHistory(mod);
            return null;
        });
    }

    @Override
    public void pin(String id, int build, boolean pinned) {
        SessionMod mod = this.mods.get(id);
        if (mod != null) {
            synchronized (mod) {
                if (pinned) {
                    mod.pinned.add(build);
                } else {
                    mod.pinned.remove(build);
                }
            }
            this.saveHistory(mod);
        }
    }

    @Override
    public void rename(String id, int build, String name) {
        SessionMod mod = this.mods.get(id);
        if (mod != null) {
            String plain = name.strip();
            synchronized (mod) {
                if (plain.isEmpty()) {
                    mod.names.remove(build);
                } else {
                    mod.names.put(build, plain.length() > NAME_MOST ? plain.substring(0, NAME_MOST) : plain);
                }
            }
            this.saveHistory(mod);
        }
    }

    @Override
    public @Nullable String saveJar(String id, int build) {
        SessionMod mod = this.mods.get(id);
        if (mod == null) {
            return null;
        }
        Path jar;
        synchronized (mod) {
            jar = build == mod.runningNumber ? mod.running.getJar() : mod.saved.stream()
                    .filter(saved -> saved.number() == build).map(SavedBuild::jar).findFirst().orElse(null);
        }
        if (jar == null && build == 0) {
            jar = this.folder.resolve(id).resolve("build-0.jar");
        }
        DevBuild chosen = jar == null ? null : DevBuild.readIndex(jar);
        if (chosen == null) {
            return null;
        }
        Path out = FabricLoader.getInstance().getGameDir().resolve("hotwire").resolve("exported")
                .resolve(id + "-build-" + build + ".jar");
        try {
            Files.createDirectories(out.getParent());

            synchronized (mod.taking) {
                Path whole = chosen.ensureJar();
                Files.copy(whole, out, StandardCopyOption.REPLACE_EXISTING);

                if (build != mod.runningNumber && !whole.equals(mod.startJar)
                        && !"build-0.jar".equals(String.valueOf(whole.getFileName()))) {
                    chosen.dropJar();
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] build {} of {} could not be saved as a jar", build, id, e);
            Notices.show(Notice.ofBad(Lang.get("hotwire.dev.saved_jar.failed", build), String.valueOf(e.getMessage())));
            return null;
        }
        Notices.show(Notice.ofDone(Lang.get("hotwire.dev.saved_jar", build), Lang.get(DEV_NAMES_ONLY
                ? "hotwire.dev.saved_jar.dev_only" : "hotwire.dev.saved_jar.line", out.getFileName())));
        WebLinks.openFolder(out.getParent());
        return out.toString();
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
        SessionMod mod = this.mods.get(id);
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
            for (SessionMod mod : this.mods.values()) {
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
        for (SessionMod mod : this.mods.values()) {
            CompareWindow comparing = mod.comparing;
            if (comparing != null) {
                comparing.stop();
            }

            synchronized (mod.taking) {
                this.cleared.delete(mod);
                DevBuildEdits.collectStore(this.folder.resolve(mod.mod.id()));
            }
        }
    }

    public static void watchLeftWorld(Object world) {
        DevSession session = current;
        if (session != null) {
            WorldLeaks.watch(world, session::findLoadedClasses, session::onLeak);
        }
    }

    private Map<String, List<Class<?>>> findLoadedClasses() {
        Map<String, List<Class<?>>> loaded = new LinkedHashMap<>();
        for (SessionMod mod : this.mods.values()) {
            Set<String> names = mod.running.getClassNames();

            loaded.put(mod.mod.id(), this.engine.findLoaded(names).stream().filter(this.engine::isInitialized)
                    .toList());
        }
        return loaded;
    }

    private void onLeak(WorldLeaks.Holder holder) {
        SessionMod mod = this.mods.get(holder.mod());
        if (mod == null) {
            return;
        }
        String title = Lang.get("hotwire.dev.leak", mod.mod.name());
        mod.addEvent(new DevEvent(System.currentTimeMillis(), DevEvent.Kind.LEAK, title,
                List.of(holder.path(), Lang.get("hotwire.dev.leak.fix"))));
        Notices.show(Notice.ofBad(title, holder.path()));
    }

    public List<String> findMarkLines() {
        List<String> lines = new ArrayList<>();
        for (SessionMod mod : this.mods.values()) {
            String named;
            synchronized (mod) {
                named = mod.names.get(mod.runningNumber);
            }
            lines.add(BuildMark.describe(mod.mod.name(), mod.runningNumber, named));
        }
        return lines;
    }

    private void takeSources(SessionMod mod, int number) {
        BuildSources sources = mod.buildSources;
        if (sources == null) {
            return;
        }
        try {
            long added = sources.take(number);
            LOGGER.debug("[Hotwire] build {} of {} kept its sources, {} bytes new", number, mod.mod.id(), added);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[Hotwire] the sources of build {} of {} could not be kept", number, mod.mod.id(), e);
        }
    }
}
