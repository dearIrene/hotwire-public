package dev.moon.hotwire.fabric.dev;

import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.dev.DevCrash;
import dev.moon.hotwire.dev.DevEvent;
import dev.moon.hotwire.dev.DevHost;
import dev.moon.hotwire.dev.DevModView;
import dev.moon.hotwire.dev.DevState;
import dev.moon.hotwire.fabric.update.ModUpdater;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.Notice;
import dev.moon.hotwire.ui.Notices;
import dev.moon.hotwire.ui.UpdateMethod;
import dev.moon.hotwire.ui.UpdatePreview;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DevSession implements DevHost, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DevSession.class);
    private static final int KEPT_EVENTS = 40;
    private static final int KEPT_CRASHES = 20;
    private static final int LISTED = 6;
    private static volatile @Nullable DevSession current;

    private static final Map<String, Start> STARTS = new ConcurrentHashMap<>();

    private record Start(DevMod mod, DevBuild build) {
    }

    public interface Engine {
        UpdatePreview preview(Path jar);

        ModUpdater.Result updateInPlace(Path jar);

        String describe(ModUpdater.Result result);

        @Nullable String restart(String id);

        boolean isEveryReloadShown();
    }

    private static final class Mod {
        final DevMod mod;
        DevBuild running;
        int runningNumber;
        long builtAt = System.currentTimeMillis();
        int taken;
        volatile DevState state = DevState.UP_TO_DATE;
        volatile String line = "";
        final Deque<DevEvent> events = new ArrayDeque<>();
        final Map<String, DevCrash> crashes = new LinkedHashMap<>();

        Mod(DevMod mod, DevBuild running) {
            this.mod = mod;
            this.running = running;
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
        DevBuild build = DevBuild.take(mod.folders(), folder.resolve(mod.id()).resolve("build-0.jar"));
        STARTS.put(mod.id(), new Start(mod, build));
        return build.getJar();
    }

    public static @Nullable DevSession start(Path folder, Tasks tasks, Engine engine) {
        if (STARTS.isEmpty()) {
            return null;
        }
        try {
            DevSession session = new DevSession(folder, tasks, engine);
            current = session;
            LOGGER.info("[Hotwire] developer mode watches the build folders of {}", STARTS.keySet());
            return session;
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] developer mode could not watch the build folders", e);
            return null;
        }
    }

    public static boolean isDevMod(String id) {
        return STARTS.containsKey(id);
    }

    public static void onCrash(String id, String what, String where) {
        DevSession session = current;
        Mod mod = session == null ? null : session.mods.get(id);
        if (mod == null) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean first;
        synchronized (mod) {
            String key = what + " @ " + where;
            DevCrash before = mod.crashes.get(key);
            first = before == null;
            mod.crashes.put(key, before == null ? new DevCrash(what, where, 1, now, now)
                    : new DevCrash(what, where, before.count() + 1, before.firstAt(), now));
            while (mod.crashes.size() > KEPT_CRASHES) {
                mod.crashes.remove(mod.crashes.keySet().iterator().next());
            }
            if (first) {
                add(mod, new DevEvent(now, DevEvent.Kind.CRASH, Lang.get("hotwire.dev.crash", mod.mod.name()),
                        List.of(what, where)));
            }
        }
        if (first) {
            Notices.show(Notice.ofBad(Lang.get("hotwire.dev.crash", mod.mod.name()), where));
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
        synchronized (mod) {
            int number = mod.taken + 1;
            Path jar = this.folder.resolve(mod.mod.id()).resolve("build-" + number + ".jar");
            DevBuild built;
            try {
                built = DevBuild.take(mod.mod.folders(), jar);
            } catch (IOException e) {
                this.fail(mod, List.of(String.valueOf(e.getMessage())));
                return;
            }
            DevBuild.Diff diff = mod.running.diff(built);
            if (diff.isEmpty()) {
                deleteQuietly(jar);
                if (mod.state == DevState.WAITING) {
                    mod.state = DevState.UP_TO_DATE;
                }
                return;
            }
            mod.taken = number;
            mod.state = DevState.LOADING;
            mod.line = Lang.get("hotwire.dev.loading");
            long began = System.nanoTime();
            UpdatePreview preview = this.engine.preview(jar);
            if (preview instanceof UpdatePreview.Ready ready && ready.method() == UpdateMethod.IN_PLACE) {
                ModUpdater.Result result = this.engine.updateInPlace(jar);
                if (result instanceof ModUpdater.Result.Updated) {
                    this.accept(mod, built, number);
                    if (diff.hasClasses()) {
                        List<String> lines = new ArrayList<>(list(diff.changedClasses()));
                        lines.addAll(list(diff.addedClasses()));
                        this.tell(mod, DevEvent.Kind.IN, Lang.get("hotwire.dev.in", mod.mod.name()),
                                Lang.get("hotwire.dev.took", describeClasses(diff), findSeconds(began)), lines);
                    } else {
                        this.tell(mod, DevEvent.Kind.ASSETS, Lang.get("hotwire.dev.assets", mod.mod.name()),
                                Lang.get("hotwire.dev.took", describeFiles(diff), findSeconds(began)),
                                list(diff.changedFiles()));
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

    private void accept(Mod mod, DevBuild built, int number) {
        DevBuild before = mod.running;
        mod.running = built;
        mod.runningNumber = number;
        mod.builtAt = System.currentTimeMillis();
        mod.state = DevState.UP_TO_DATE;
        mod.line = "";

        try (DirectoryStream<Path> jars = Files.newDirectoryStream(built.getJar().getParent(), "build-*.jar")) {
            for (Path jar : jars) {
                if (!jar.equals(built.getJar())) {
                    deleteQuietly(jar);
                }
            }
        } catch (IOException e) {
            LOGGER.debug("[Hotwire] the builds before {} could not be listed", built.getJar(), e);
        }
        LOGGER.info("[Hotwire] {} runs build {} now (was {})", mod.mod.id(), built.getJar().getFileName(),
                before.getJar().getFileName());
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
            while (mod.events.size() > KEPT_EVENTS) {
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
            synchronized (mod) {
                views.add(new DevModView(mod.mod.id(), mod.mod.name(), mod.state, mod.line, mod.runningNumber,
                        mod.builtAt, List.copyOf(mod.events), List.copyOf(mod.crashes.values()).reversed()));
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
            long began = System.nanoTime();
            mod.state = DevState.LOADING;
            mod.line = Lang.get("hotwire.dev.restarting");
            String why = this.engine.restart(id);
            if (why != null) {
                this.fail(mod, List.of(why));
            } else {
                mod.state = DevState.UP_TO_DATE;
                mod.line = "";
                this.tell(mod, DevEvent.Kind.RESTARTED, Lang.get("hotwire.dev.restarted", mod.mod.name()),
                        Lang.get("hotwire.dev.took.only", findSeconds(began)), List.of());
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

    public static SortedSet<String> findIds() {
        DevSession session = current;
        return session == null ? new TreeSet<>() : new TreeSet<>(session.mods.keySet());
    }
}
