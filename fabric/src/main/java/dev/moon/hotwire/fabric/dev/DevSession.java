package dev.moon.hotwire.fabric.dev;

import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.dev.BuildNotes;
import dev.moon.hotwire.dev.DevBuild;
import dev.moon.hotwire.dev.DevCrash;
import dev.moon.hotwire.dev.DevEvent;
import dev.moon.hotwire.dev.DevHost;
import dev.moon.hotwire.dev.DevMixin;
import dev.moon.hotwire.dev.DevModView;
import dev.moon.hotwire.dev.DevState;
import dev.moon.hotwire.dev.IdeOpener;
import dev.moon.hotwire.dev.WorldLeaks;
import dev.moon.hotwire.fabric.rebuild.GameClasses;
import dev.moon.hotwire.fabric.update.ModUpdater;
import dev.moon.hotwire.redefine.ClassRebuilder;
import dev.moon.hotwire.ui.Lang;
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
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DevSession implements DevHost, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DevSession.class);
    private static final int MOST_EVENTS = 40;
    private static final int MOST_CRASHES = 20;

    private static final float CRASH_REST_SECONDS = 10;

    private static final long REMOVAL_WAIT_MILLIS = 2000;

    private static final long HALF_WAIT_MILLIS = 300;
    private static final int HALF_TRIES = 10;
    private static final int LISTED = 6;
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

        List<Class<?>> findLoaded(Set<String> names);

        List<DevMixin> findMixins(String id);

        List<String> redefineElsewhere(Map<String, byte[]> classes);
    }

    private static final class Mod {
        final DevMod mod;

        final Object taking = new Object();
        volatile DevBuild running;
        volatile int runningNumber;
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
        Set<Path> stays = Set.of(staying);
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

        Notice card = findCrashCard(mod, count == 1 ? Lang.get("hotwire.dev.crash.at", kind, place)
                : Lang.get("hotwire.dev.crash.times", kind, place, count));
        if (mod.crashTold) {
            Notices.replace(card);
        } else {
            mod.crashTold = true;
            Notices.show(card);
        }
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
                    this.accept(mod, built, number);
                    if (diff.hasClasses()) {
                        List<String> lines = new ArrayList<>();
                        if (!diff.changedClasses().isEmpty()) {
                            lines.add(Lang.get("hotwire.dev.changed", String.join(", ", list(diff.changedClasses()))));
                        }
                        if (!diff.addedClasses().isEmpty()) {
                            lines.add(Lang.get("hotwire.dev.added", String.join(", ", list(diff.addedClasses()))));
                        }
                        List<String> notes = this.findNotes(mod, diff, was, classes);
                        lines.addAll(notes);
                        String detail = notes.isEmpty() ? Lang.get("hotwire.dev.took", describeClasses(diff),
                                findSeconds(began)) : notes.getFirst();
                        this.tell(mod, DevEvent.Kind.IN, Lang.get("hotwire.dev.in", mod.mod.name()), detail, lines);
                    } else {
                        this.tell(mod, DevEvent.Kind.ASSETS, Lang.get("hotwire.dev.assets", mod.mod.name()),
                                Lang.get("hotwire.dev.took", describeFiles(diff), findSeconds(began)),
                                List.of(Lang.get("hotwire.dev.changed", String.join(", ", list(diff.changedFiles())))));
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
                this.fail(mod, List.of(describePreview(preview)));
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
        mod.running = built;
        mod.runningNumber = number;
        mod.builtAt = System.currentTimeMillis();
        mod.state = DevState.UP_TO_DATE;
        mod.line = "";

        Path start = built.getJar().resolveSibling("build-0.jar");
        deleteBuildsBut(built.getJar(), start, start.resolveSibling("build-0.jar.idx"));
        LOGGER.info("[Hotwire] {} runs build {} now (was {})", mod.mod.id(), built.getJar().getFileName(),
                before.getJar().getFileName());
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
        add(mod, new DevEvent(System.currentTimeMillis(), kind, title, lines));
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

    private static List<String> list(SortedSet<String> names) {
        List<String> shown = new ArrayList<>();
        for (String name : names) {
            if (shown.size() == LISTED) {
                shown.add(Lang.get("hotwire.dev.and_more", names.size() - LISTED));
                break;
            }
            String plain = name.endsWith(".class") ? name.substring(0, name.length() - ".class".length()) : name;
            shown.add(plain.substring(plain.lastIndexOf('/') + 1));
        }
        return shown;
    }

    private static String describeClasses(DevBuild.Diff diff) {
        int count = diff.changedClasses().size() + diff.addedClasses().size() + diff.removedClasses().size();
        return count == 1 ? Lang.get("hotwire.dev.class.one") : Lang.get("hotwire.dev.class.many", count);
    }

    private static String describeFiles(DevBuild.Diff diff) {
        int count = diff.changedFiles().size();
        return count == 1 ? Lang.get("hotwire.dev.file.one") : Lang.get("hotwire.dev.file.many", count);
    }

    private static String findSeconds(long began) {
        return String.format(Locale.ROOT, "%.1f s", (System.nanoTime() - began) / 1e9);
    }

    private static String describePreview(UpdatePreview preview) {
        return switch (preview) {
            case UpdatePreview.Refused refused -> refused.reason();
            case UpdatePreview.SameVersion same -> Lang.get("hotwire.dev.same");
            case UpdatePreview.Unreadable unreadable -> Lang.get("hotwire.dev.unreadable");
            case UpdatePreview.Ready ready -> String.join(" ", ready.lines());
        };
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
                        mod.builtAt, List.copyOf(mod.events), List.copyOf(mod.crashes.values()).reversed(), mixins));
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
                            done.leftOver().isEmpty() ? Lang.get("hotwire.dev.took.only", findSeconds(began))
                                    : Lang.get("hotwire.dev.left_over.count", done.leftOver().size()), lines);
                }
            }
            return null;
        });
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void openSource(String id, DevCrash crash) {
        Mod mod = this.mods.get(id);
        if (mod == null || crash.source().isEmpty()) {
            return;
        }
        this.tasks.submit("opening " + crash.where(), () -> {
            List<Path> projects = new ArrayList<>();
            for (Path folder : mod.mod.folders()) {
                Path project = DevMod.findProject(folder);
                if (project != null && !projects.contains(project)) {
                    projects.add(project);
                }
            }
            Path file;
            try {
                file = IdeOpener.findSource(projects, crash.source());
            } catch (IOException e) {
                LOGGER.debug("[Hotwire] the project could not be searched for {}", crash.source(), e);
                file = null;
            }
            if (file == null) {
                Notices.show(Notice.ofBad(Lang.get("hotwire.dev.no_source", crash.where()), ""));
            } else if (IdeOpener.open(file, crash.line())) {
                Notices.show(Notice.ofDone(Lang.get("hotwire.dev.opened", crash.where()), ""));
            } else {
                String place = file + ":" + crash.line();
                Minecraft game = Minecraft.getInstance();
                game.execute(() -> game.keyboardHandler.setClipboard(place));
                Notices.show(Notice.ofOn(Lang.get("hotwire.dev.copied"), place));
            }
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
