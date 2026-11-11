package dev.moon.hotwire.dev;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

public final class DevBridge implements Function<String, String> {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final long POLL_MILLIS = 150;
    private static final long LOOK_BACK_MILLIS = 10_000;
    private static final long CHANGE_WAIT_MILLIS = 60_000;

    private static final long FIRST_THROWS_MILLIS = 2_000;
    private static final int MOST_LOG_LINES = 500;
    private static final int LOG_TAIL_BYTES = 512 * 1024;

    private final DevHost host;
    private final Supplier<@Nullable Path> log;
    private final List<Path> projects;
    private final LongSupplier clock;

    private final Map<String, Long> told = new ConcurrentHashMap<>();

    public DevBridge(DevHost host, Supplier<@Nullable Path> log, List<Path> projects) {
        this(host, log, projects, System::currentTimeMillis);
    }

    DevBridge(DevHost host, Supplier<@Nullable Path> log, List<Path> projects, LongSupplier clock) {
        this.host = host;
        this.log = log;
        this.projects = List.copyOf(projects);
        this.clock = clock;
    }

    @Override
    public String apply(String request) {
        try {
            JsonObject asked = JsonParser.parseString(request).getAsJsonObject();
            String tool = asked.has("tool") ? asked.get("tool").getAsString() : "";
            JsonObject args = asked.has("args") && asked.get("args").isJsonObject() ? asked.getAsJsonObject("args")
                    : new JsonObject();
            return GSON.toJson(this.run(tool, args));
        } catch (RuntimeException e) {
            return GSON.toJson(fail(e.getMessage() == null ? e.toString() : e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return GSON.toJson(fail("interrupted"));
        }
    }

    private JsonElement run(String tool, JsonObject args) throws InterruptedException {
        return switch (tool) {
            case "list" -> DevTools.list();
            case "projects" -> this.findProjects();
            case "status" -> this.findStatus();
            case "builds" -> this.findBuilds(this.findMod(args));
            case "build_report" -> this.findReport(this.findMod(args), getInt(args, "build"));
            case "wait_for_build" -> this.waitForBuild(this.findMod(args), args.has("timeout_seconds")
                    ? Math.clamp(args.get("timeout_seconds").getAsLong(), 1, 600) : 120);
            case "roll_back" -> this.revertTo(this.findMod(args), getInt(args, "build"));
            case "restore" -> this.restore(this.findMod(args), getInt(args, "build"));
            case "undo_restore" -> this.restoreSavePoint(this.findMod(args));
            case "code" -> {
                DevModView mod = this.findMod(args);
                String code = this.host.findCode(mod.id(), getInt(args, "build"), getString(args, "class"),
                        getString(args, "method"), isTrue(args, "before"));
                yield code == null ? fail("no kept sources of that method in that build") : GSON.toJsonTree(code);
            }
            case "merge_preview" -> this.previewMerge(this.findMod(args), getInts(args, "builds"));
            case "merge" -> this.merge(this.findMod(args), getInts(args, "builds"), isTrue(args, "confirm"),
                    isTrue(args, "anyway"));
            case "pin" -> {
                DevModView mod = this.findMod(args);
                this.host.pin(mod.id(), getInt(args, "build"), !args.has("pinned") || isTrue(args, "pinned"));
                yield this.findBuilds(this.findMod(args));
            }
            case "rename" -> {
                DevModView mod = this.findMod(args);
                this.host.rename(mod.id(), getInt(args, "build"), args.has("name") ? args.get("name").getAsString()
                        : "");
                yield this.findBuilds(this.findMod(args));
            }
            case "copy_build" -> this.copy(this.findMod(args), getInt(args, "build"));
            case "save_jar" -> {
                DevModView mod = this.findMod(args);
                String saved = this.host.saveJar(mod.id(), getInt(args, "build"));
                if (saved == null) {
                    yield fail("build " + getInt(args, "build") + " is not kept; builds lists those that are");
                }
                JsonObject out = new JsonObject();
                out.addProperty("saved", saved);
                yield out;
            }
            case "ab" -> this.runCompare(this.findMod(args), args.has("action") ? args.get("action").getAsString()
                    : "status", args.has("build") ? args.get("build").getAsInt() : -1);
            case "crashes" -> this.findCrashes(this.findMod(args));
            case "history" -> this.findHistory(this.findMod(args), args.has("limit")
                    ? Math.clamp(args.get("limit").getAsInt(), 1, 100) : 20);
            case "mixins" -> this.findMixins(this.findMod(args));
            case "reload_now" -> {
                DevModView mod = this.findMod(args);
                this.host.reloadNow(mod.id());
                yield makeOk("asked for " + mod.name() + "'s newest build; wait_for_build tells how it went");
            }
            case "restart_mod" -> {
                DevModView mod = this.findMod(args);
                this.host.restartMod(mod.id());
                yield makeOk("restarting " + mod.name() + "; wait_for_build tells how it went");
            }
            case "set_watching" -> {
                this.host.setWatching(isTrue(args, "on"));
                yield makeOk("watching builds: " + this.host.isWatching());
            }
            case "game_log" -> this.readLog(args.has("lines") ? Math.clamp(args.get("lines").getAsInt(), 1,
                    MOST_LOG_LINES) : 80, args.has("contains") ? args.get("contains").getAsString() : "");
            default -> fail("no tool named " + tool);
        };
    }

    private DevModView findMod(JsonObject args) {
        List<DevModView> mods = this.host.getDevMods();
        if (mods.isEmpty()) {
            throw new IllegalStateException("no mod runs from its build folders in this game");
        }
        if (!args.has("mod")) {
            return mods.getFirst();
        }
        String asked = args.get("mod").getAsString();
        return mods.stream().filter(mod -> mod.id().equals(asked) || mod.name().equalsIgnoreCase(asked)).findFirst()
                .orElseThrow(() -> new IllegalStateException("no mod in development named " + asked + "; there are "
                        + mods.stream().map(DevModView::id).toList()));
    }

    private JsonElement findProjects() {
        JsonArray out = new JsonArray();
        this.projects.forEach(project -> out.add(project.toString()));
        return out;
    }

    private JsonElement findStatus() {
        JsonObject out = new JsonObject();
        out.addProperty("watching_builds", this.host.isWatching());
        JsonArray mods = new JsonArray();
        for (DevModView mod : this.host.getDevMods()) {
            JsonObject one = new JsonObject();
            one.addProperty("id", mod.id());
            one.addProperty("name", mod.name());
            one.addProperty("state", mod.state().name());
            one.addProperty("line", mod.line());
            one.addProperty("running_build", mod.runningBuild());
            one.addProperty("kept_builds", mod.savedBuilds().size());
            one.addProperty("crashes", mod.crashes().size());
            mods.add(one);
        }
        out.add("mods", mods);
        return out;
    }

    private JsonElement findBuilds(DevModView mod) {
        JsonObject out = new JsonObject();
        out.addProperty("running_build", mod.runningBuild());
        JsonArray builds = new JsonArray();
        List<Integer> all = new ArrayList<>(mod.savedBuilds());
        if (!all.contains(mod.runningBuild())) {
            all.add(mod.runningBuild());
        }
        all.sort(null);
        for (int build : all.reversed()) {
            JsonObject one = new JsonObject();
            one.addProperty("build", build);
            String name = mod.names().get(build);
            if (name != null) {
                one.addProperty("name", name);
            }
            Integer parent = mod.parents().get(build);
            if (parent != null) {
                one.addProperty("made_on", parent);
            }
            one.addProperty("running", build == mod.runningBuild());
            one.addProperty("pinned", mod.pinned().contains(build));
            DevBuildInfo info = mod.infos().get(build);
            if (info != null && !info.merged().isEmpty()) {
                one.add("merged_from", GSON.toJsonTree(info.merged()));
            }
            List<String> threw = mod.throwsByBuild().get(build);
            one.addProperty("threw", threw != null && !threw.isEmpty());
            builds.add(one);
        }
        out.add("builds", builds);
        return out;
    }

    private JsonElement findReport(DevModView mod, int build) {
        if (build != mod.runningBuild() && !mod.savedBuilds().contains(build)) {
            return fail("build " + build + " is not kept; builds lists those that are");
        }
        JsonObject out = new JsonObject();
        out.addProperty("build", build);
        DevBuildInfo info = mod.infos().get(build);
        if (info == null) {
            out.addProperty("note", build == 0 ? "the game started on this build: nothing before it to compare with"
                    : "no report of this build");
        } else {
            out.addProperty("made_on", info.madeOn());
            out.add("merged_from", GSON.toJsonTree(info.merged()));
            JsonArray classes = new JsonArray();
            for (DevClassChange change : info.classes()) {
                JsonObject one = new JsonObject();
                one.addProperty("class", change.type().isEmpty() ? change.name() : change.type());
                one.addProperty("file", change.file());
                one.add("changed_methods", this.withLines(change, change.changed()));
                one.add("new_methods", this.withLines(change, change.added()));
                one.add("removed_methods", GSON.toJsonTree(change.removed()));
                if (!change.diffs().isEmpty()) {
                    one.add("diffs", GSON.toJsonTree(change.diffs()));
                }
                classes.add(one);
            }
            out.add("changed_classes", classes);
            out.add("new_classes", GSON.toJsonTree(info.addedClasses()));
            out.add("removed_classes", GSON.toJsonTree(info.removedClasses()));
            out.add("files", GSON.toJsonTree(info.files()));
            out.add("notes", GSON.toJsonTree(info.notes()));
            if (!info.git().isEmpty()) {
                out.addProperty("git", info.git());
                out.addProperty("uncommitted", info.uncommitted());
            }
        }
        out.add("threw", GSON.toJsonTree(mod.throwsByBuild().getOrDefault(build, List.of())));
        return out;
    }

    private JsonArray withLines(DevClassChange change, List<String> methods) {
        JsonArray out = new JsonArray();
        for (String method : methods) {
            List<Integer> span = change.lines().get(method);
            out.add(span == null ? method : method + " (lines " + span.get(0) + "-" + span.get(1) + ")");
        }
        return out;
    }

    private JsonElement waitForBuild(DevModView mod, long seconds) throws InterruptedException {
        long asked = this.clock.getAsLong();

        long since = Math.max(asked - LOOK_BACK_MILLIS, this.told.getOrDefault(mod.id(), -1L) + 1);
        long until = asked + seconds * 1000;
        while (this.clock.getAsLong() < until) {
            DevModView now = this.findById(mod.id());
            DevEvent event = now == null ? null : findEventSince(now, since, Set.of(DevEvent.Kind.IN,
                    DevEvent.Kind.ASSETS, DevEvent.Kind.NEEDS_RESTART, DevEvent.Kind.FAILED));
            if (now != null && event != null) {
                this.told.put(mod.id(), event.at());
                if (event.kind() == DevEvent.Kind.IN || event.kind() == DevEvent.Kind.ASSETS) {
                    Thread.sleep(FIRST_THROWS_MILLIS);
                    now = this.findById(mod.id());
                }
                JsonObject out = describe(event);
                if (now != null && event.build() >= 0) {
                    out.add("report", this.findReport(now, event.build()));
                }
                if (now != null) {
                    out.add("crashes_since", this.findCrashesSince(now, since));
                }
                return out;
            }
            Thread.sleep(POLL_MILLIS);
        }
        JsonObject out = new JsonObject();
        out.addProperty("timed_out", true);
        out.addProperty("note", "no build went in within " + seconds + " s; builds go in once the compiler writes "
                + "them (a Gradle classes task, or a build in the IDE)");
        return out;
    }

    private JsonElement revertTo(DevModView mod, int build) throws InterruptedException {
        if (build == mod.runningBuild()) {
            return fail("build " + build + " runs already");
        }
        if (!mod.savedBuilds().contains(build)) {
            return fail("build " + build + " is not kept; builds lists those that are");
        }
        long since = this.clock.getAsLong();
        this.host.revertTo(mod.id(), build);
        return this.awaitChange(mod.id(), since, Set.of(DevEvent.Kind.IN, DevEvent.Kind.FAILED));
    }

    private JsonElement restore(DevModView mod, int build) throws InterruptedException {
        if (!mod.hasBuild(build)) {
            return fail("build " + build + " is not kept; builds lists those that are");
        }
        long since = this.clock.getAsLong();
        this.host.restore(mod.id(), build);
        return this.awaitChange(mod.id(), since, Set.of(DevEvent.Kind.IN, DevEvent.Kind.FAILED));
    }

    private JsonElement restoreSavePoint(DevModView mod) throws InterruptedException {
        if (!mod.canUndo()) {
            return fail("no restore to undo");
        }
        long since = this.clock.getAsLong();
        this.host.restoreSavePoint(mod.id());
        return this.awaitChange(mod.id(), since, Set.of(DevEvent.Kind.IN, DevEvent.Kind.FAILED));
    }

    private JsonElement previewMerge(DevModView mod, List<Integer> builds) {
        BuildMerge.Plan plan = planMerge(mod, builds);
        JsonObject out = new JsonObject();
        BuildMerge.Refusal refusal = plan.refusal();
        if (refusal != null) {
            out.addProperty("refused", refusal.kind().name());
            out.addProperty("build", refusal.build());
            return out;
        }
        out.add("builds", GSON.toJsonTree(plan.builds()));
        out.addProperty("made_on", plan.parent());
        out.addProperty("keeps_newest_as_is", plan.isFast());
        out.add("files", GSON.toJsonTree(plan.sources()));
        out.add("files_taken_out", GSON.toJsonTree(new TreeSet<>(plan.gone())));
        JsonArray conflicts = new JsonArray();
        for (BuildMerge.Conflict conflict : plan.conflicts()) {
            JsonObject one = new JsonObject();
            one.addProperty("file", conflict.entry());
            one.add("written_by", GSON.toJsonTree(conflict.builds()));
            one.addProperty("kept_from", conflict.winner());
            one.add("thrown_away_from", GSON.toJsonTree(conflict.dropped()));
            conflicts.add(one);
        }
        out.add("conflicts", conflicts);
        JsonArray left = new JsonArray();
        for (BuildMerge.BuildGap one : plan.leftOut()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("build", one.build());
            entry.add("made_on_left_out", GSON.toJsonTree(one.skipped()));
            entry.add("changes_not_in_merge", GSON.toJsonTree(one.entries()));
            left.add(entry);
        }
        out.add("left_out", left);
        out.add("builds_thrown_away", GSON.toJsonTree(new TreeSet<>(plan.removed())));
        return out;
    }

    private JsonElement merge(DevModView mod, List<Integer> builds, boolean confirm, boolean anyway)
            throws InterruptedException {
        BuildMerge.Plan plan = planMerge(mod, builds);
        if (plan.refusal() != null || !confirm) {
            JsonObject out = this.previewMerge(mod, builds).getAsJsonObject();
            out.addProperty("merged", false);
            out.addProperty("note", plan.refusal() != null ? "this merge cannot be made"
                    : "nothing was merged: call merge again with confirm true once the builds thrown away are fine");
            return out;
        }
        if (!plan.conflicts().isEmpty() && !anyway) {
            JsonObject out = this.previewMerge(mod, builds).getAsJsonObject();
            out.addProperty("merged", false);
            out.addProperty("note", "files conflict: call merge again with anyway true to keep the newer versions");
            return out;
        }
        long since = this.clock.getAsLong();
        this.host.merge(mod.id(), List.copyOf(builds), anyway);
        return this.awaitChange(mod.id(), since, Set.of(DevEvent.Kind.MERGED, DevEvent.Kind.FAILED));
    }

    private static BuildMerge.Plan planMerge(DevModView mod, List<Integer> builds) {
        Set<Integer> there = new HashSet<>(mod.savedBuilds());
        there.add(mod.runningBuild());
        there.add(0);
        return BuildMerge.plan(builds, mod.parents(), mod.deltas(), there, mod.runningBuild(), mod.pinned());
    }

    private JsonElement copy(DevModView mod, int build) throws InterruptedException {
        if (build == 0 || (build != mod.runningBuild() && !mod.savedBuilds().contains(build))) {
            return fail("build " + build + " cannot be copied");
        }
        long since = this.clock.getAsLong();
        this.host.copyBuild(mod.id(), build);
        return this.awaitChange(mod.id(), since, Set.of(DevEvent.Kind.MERGED, DevEvent.Kind.FAILED));
    }

    private JsonElement awaitChange(String id, long since, Set<DevEvent.Kind> kinds) throws InterruptedException {
        long until = since + CHANGE_WAIT_MILLIS;
        while (this.clock.getAsLong() < until) {
            DevModView now = this.findById(id);
            DevEvent event = now == null ? null : findEventSince(now, since, kinds);
            if (event != null) {
                this.told.put(id, event.at());
                return describe(event);
            }
            Thread.sleep(POLL_MILLIS);
        }
        return fail("asked, and nothing was heard back in " + CHANGE_WAIT_MILLIS / 1000 + " s; status and history"
                + " say where it stands");
    }

    private JsonElement findCrashes(DevModView mod) {
        JsonArray out = new JsonArray();
        mod.crashes().forEach(crash -> out.add(describe(crash)));
        return out;
    }

    private JsonArray findCrashesSince(DevModView mod, long since) {
        JsonArray out = new JsonArray();
        mod.crashes().stream().filter(crash -> crash.lastAt() >= since).forEach(crash -> out.add(describe(crash)));
        return out;
    }

    private JsonElement findHistory(DevModView mod, int limit) {
        JsonArray out = new JsonArray();
        mod.events().stream().limit(limit).forEach(event -> out.add(describe(event)));
        return out;
    }

    private JsonElement findMixins(DevModView mod) {
        JsonArray out = new JsonArray();
        for (DevMixin mixin : mod.mixins()) {
            JsonObject one = new JsonObject();
            one.addProperty("target", mixin.target());
            one.add("also_changed_by", GSON.toJsonTree(mixin.others()));
            out.add(one);
        }
        return out;
    }

    private JsonElement readLog(int lines, String contains) {
        Path file = this.log.get();
        if (file == null || !Files.isRegularFile(file)) {
            return fail("the game's log was not found");
        }
        try (RandomAccessFile read = new RandomAccessFile(file.toFile(), "r")) {
            long length = read.length();
            long from = Math.max(0, length - LOG_TAIL_BYTES);
            byte[] bytes = new byte[(int) (length - from)];
            read.seek(from);
            read.readFully(bytes);
            List<String> all = new ArrayList<>(List.of(new String(bytes, StandardCharsets.UTF_8).split("\r?\n")));
            if (from > 0 && !all.isEmpty()) {
                all.removeFirst();
            }
            if (!contains.isEmpty()) {
                all.removeIf(line -> !line.contains(contains));
            }
            JsonObject out = new JsonObject();
            out.add("lines", GSON.toJsonTree(all.subList(Math.max(0, all.size() - lines), all.size())));
            return out;
        } catch (IOException e) {
            return fail("the game's log could not be read: " + e.getMessage());
        }
    }

    private @Nullable DevModView findById(String id) {
        return this.host.getDevMods().stream().filter(mod -> mod.id().equals(id)).findFirst().orElse(null);
    }

    private static @Nullable DevEvent findEventSince(DevModView mod, long since, Set<DevEvent.Kind> kinds) {
        DevEvent found = null;
        for (DevEvent event : mod.events()) {
            if (event.at() < since) {
                break;
            }
            if (kinds.contains(event.kind())) {
                found = event;
            }
        }
        return found;
    }

    private static JsonObject describe(DevEvent event) {
        JsonObject out = new JsonObject();
        out.addProperty("what", event.kind().name());
        out.addProperty("title", event.title());
        out.add("lines", GSON.toJsonTree(event.lines()));
        if (event.build() >= 0) {
            out.addProperty("build", event.build());
        }
        return out;
    }

    private static JsonObject describe(DevCrash crash) {
        JsonObject out = new JsonObject();
        out.addProperty("what", crash.what());
        out.addProperty("where", crash.where());
        out.addProperty("times", crash.count());
        if (!crash.source().isEmpty()) {
            out.addProperty("class", crash.source());
            out.addProperty("line", crash.line());
        }
        return out;
    }

    private JsonElement runCompare(DevModView mod, String action, int build) {
        switch (action) {
            case "start" -> {
                if (build < 0 || !mod.hasBuild(build) || build == mod.runningBuild()) {
                    return fail("start takes a kept build other than the running one; builds lists them");
                }
                this.host.startCompare(mod.id(), mod.runningBuild(), build);
            }
            case "switch" -> this.host.switchCompare(mod.id());
            case "stop" -> this.host.stopCompare(mod.id());
            case "status" -> {
            }
            default -> {
                return fail("action is start, switch, stop or status");
            }
        }
        DevCompare compare = this.host.getDevMods().stream().filter(one -> one.id().equals(mod.id())).findFirst()
                .map(DevModView::compare).orElse(null);
        if (compare == null) {
            return makeOk("no A/B has run");
        }
        JsonObject out = new JsonObject();
        out.addProperty("a", compare.a());
        out.addProperty("b", compare.b());
        out.addProperty("running", compare.on());
        out.addProperty("fps_a", compare.fpsA());
        out.addProperty("fps_b", compare.fpsB());
        out.addProperty("tick_ms_a", compare.tickMillisA());
        out.addProperty("tick_ms_b", compare.tickMillisB());
        out.addProperty("mb_per_second_a", compare.allocMbA());
        out.addProperty("mb_per_second_b", compare.allocMbB());
        out.addProperty("seconds_a", compare.secondsA());
        out.addProperty("seconds_b", compare.secondsB());
        out.addProperty("stopped", compare.done());
        if (!compare.failed().isEmpty()) {
            out.addProperty("failed", compare.failed());
        }
        JsonArray why = new JsonArray();
        for (DevWhy one : compare.why()) {
            JsonObject row = new JsonObject();
            row.addProperty("method", one.findName());
            row.addProperty("type", one.type());
            row.addProperty("line", one.line());
            row.addProperty("ms_per_second_a", one.timeA() * 1000);
            row.addProperty("ms_per_second_b", one.timeB() * 1000);
            row.addProperty("mb_per_second_a", one.madeA());
            row.addProperty("mb_per_second_b", one.madeB());
            why.add(row);
        }
        out.add("why", why);
        return out;
    }

    private static JsonObject makeOk(String said) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", said);
        return out;
    }

    private static JsonObject fail(String why) {
        JsonObject out = new JsonObject();
        out.addProperty("error", why);
        return out;
    }

    private static int getInt(JsonObject args, String name) {
        if (!args.has(name)) {
            throw new IllegalArgumentException("missing " + name);
        }
        return args.get(name).getAsInt();
    }

    private static String getString(JsonObject args, String name) {
        if (!args.has(name)) {
            throw new IllegalArgumentException("missing " + name);
        }
        return args.get(name).getAsString();
    }

    private static boolean isTrue(JsonObject args, String name) {
        return args.has(name) && args.get(name).getAsBoolean();
    }

    private static List<Integer> getInts(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonArray()) {
            throw new IllegalArgumentException("missing " + name + " (a list of build numbers)");
        }
        List<Integer> out = new ArrayList<>();
        args.getAsJsonArray(name).forEach(each -> out.add(each.getAsInt()));
        return out;
    }
}
