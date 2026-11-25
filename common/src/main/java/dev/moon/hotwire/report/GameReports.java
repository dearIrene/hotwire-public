package dev.moon.hotwire.report;

import dev.moon.hotwire.classfile.GameNames;
import dev.moon.hotwire.config.AtomicFiles;
import dev.moon.hotwire.engine.LoadedMods;
import dev.moon.hotwire.engine.WorldContentRecord;
import dev.moon.hotwire.plan.SwitchPlan;
import dev.moon.hotwire.registration.Owners;
import dev.moon.hotwire.store.OutdatedNotice;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.SwitchApplier;
import dev.moon.hotwire.update.ModUpdater;
import dev.moon.hotwire.update.SwapAtClose;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GameReports {
    private static final int WORLD_LOG_LINES = 3000;
    private static final DateTimeFormatter SPAN = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Logger LOGGER = LoggerFactory.getLogger(GameReports.class);

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private static final int MOST_READ = 8 * 1024 * 1024;

    private static final int NEAR_FRAMES = 12;
    private static volatile @Nullable GameReports current;

    private final Path folder;
    private final Report.Machine machine;
    private final Function<String, String> nameOf;
    private final ReportDetails details;

    private final Set<String> added = new CopyOnWriteArraySet<>();

    private final List<String> story = new CopyOnWriteArrayList<>();
    private final Map<String, String> rebuiltBy = new ConcurrentHashMap<>();

    private final Map<String, LastRebuild> lastRebuilt = new ConcurrentHashMap<>();

    public record LastRebuild(List<String> ids, long at) {
    }
    private final Set<String> redefined = new ConcurrentSkipListSet<>();
    private volatile Set<String> off = Set.of();

    private final Set<String> emptied = ConcurrentHashMap.newKeySet();
    private volatile @Nullable Report last;

    private volatile @Nullable String underWay;

    private static volatile boolean crashOffUnderWay;

    private volatile LocalTime begun = LocalTime.MIN;
    private final Map<String, String> ranWhen = new ConcurrentHashMap<>();

    private volatile @Nullable LastThrow lastThrow;

    private volatile String lastDecision = "";

    private record LastThrow(List<String> messages, List<String> frames, List<String> mods, List<String> spans) {
    }

    private final AtomicInteger depth = new AtomicInteger();
    private final List<Report.Failure> outerFaults = new CopyOnWriteArrayList<>();

    private final ExecutorService writer = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("hotwire-reports").daemon().factory());
    private final Instant began = Instant.now();

    public GameReports(Function<String, String> nameOf, ReportDetails details) {
        this.details = details;
        FabricLoader loader = FabricLoader.getInstance();
        this.folder = loader.getGameDir().resolve("hotwire").resolve("reports");
        this.nameOf = nameOf;
        this.machine = new Report.Machine(LoadedMods.findVersion(GameNames.HOTWIRE, "unknown"),
                LoadedMods.findVersion("minecraft", "unknown"), LoadedMods.findVersion("fabricloader", "unknown"),
                details.getMixinVersion(), Runtime.version().toString(),
                System.getProperty("os.name", "") + " " + System.getProperty("os.version", ""),
                "launcher agent", Map.of());
        current = this;
        ActionJournal.get().flushWith(this.writer);
    }

    private static volatile Consumer<Path> crashSaved = file -> { };

    public static void listenForCrashes(Consumer<Path> listener) {
        crashSaved = listener;
    }

    private static final long REPEAT_NANOS = 60_000_000_000L;
    private static String lastCrash = "";
    private static long lastCrashAt;
    private static int repeats;

    public static synchronized boolean countRepeat(Path file, boolean cleared) {
        String what = readWhat(file) + (cleared ? " (cleared)" : "");
        long now = System.nanoTime();
        if (what.equals(lastCrash) && now - lastCrashAt < REPEAT_NANOS) {
            repeats++;
            LOGGER.info("[Hotwire] the same crash again ({} since its report): {}", repeats, what);
            return true;
        }
        lastCrash = what;
        lastCrashAt = now;
        repeats = 0;
        return false;
    }

    public static void tellCrashSaved(Path file) {
        crashSaved.accept(file);
    }

    private static String readWhat(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.limit(12).filter(line -> line.startsWith("Description:") || line.contains("Exception")
                    || line.contains("Error")).limit(2).reduce("", (first, second) -> first + " " + second).strip();
        } catch (IOException | UncheckedIOException e) {
            return file.getFileName().toString();
        }
    }

    void markReported(Path crash) {
        Path list = FabricLoader.getInstance().getGameDir().resolve("hotwire").resolve("reported-crashes.txt");
        try {
            Files.createDirectories(list.getParent());
            Files.writeString(list, crash.getFileName() + System.lineSeparator(), StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] could not keep the name of the reported crash", e);
        }
    }

    private static Set<String> readReported(Path game) {
        try {
            return Set.copyOf(Files.readAllLines(game.resolve("hotwire").resolve("reported-crashes.txt")));
        } catch (IOException e) {
            return Set.of();
        }
    }

    public static CrashSection getCrashSection() {
        GameReports reports = current;
        if (reports == null) {
            return CrashSection.of(List.of(), List.of(), List.of(), List.of(), null, id -> id);
        }
        List<String> story = new ArrayList<>(reports.story);
        String now = reports.underWay;
        if (now != null) {
            story.add(now);
        }
        return CrashSection.of(reports.off, reports.added, List.of(), reports.redefined, reports.last, reports.nameOf,
                story);
    }

    public void onBegun(Report.Operation operation, String what) {
        List<Report.Failure> before = SwitchFaults.take();
        if (this.depth.getAndIncrement() > 0) {
            this.outerFaults.addAll(before);
            return;
        }
        this.outerFaults.clear();
        this.begun = LocalTime.now(ZoneId.systemDefault()).withNano(0);
        this.underWay = this.begun + " " + operation.getWords() + " " + what
                + ": under way, the classes it redefined so far not counted below";
    }

    public static void setCrashOffUnderWay(boolean underWay) {
        crashOffUnderWay = underWay;
    }

    public void onEnded() {
        if (this.depth.decrementAndGet() <= 0) {
            this.depth.set(0);
            this.underWay = null;
        }
    }

    public static void logCrashDecision(CrashSection.Detail trace) {
        if (!CrashSection.isProven(trace.value())) {
            LOGGER.error("[Hotwire] {}: {}", trace.name(), trace.value());
            return;
        }
        String bar = "=".repeat(100);
        LOGGER.error("[Hotwire] {}", bar);
        LOGGER.error("[Hotwire] If you are reading this, Hotwire caused this crash.");
        LOGGER.error("[Hotwire] {}: {}", trace.name(), trace.value());
        LOGGER.error("[Hotwire] {}", bar);
    }

    public static boolean isRebuilt(String binaryName) {
        GameReports reports = current;
        return reports != null && reports.rebuiltBy.containsKey(binaryName.replace('.', '/'));
    }

    public static @Nullable LastRebuild findLastRebuild(String binaryName) {
        GameReports reports = current;
        return reports == null ? null : reports.lastRebuilt.get(binaryName.replace('.', '/'));
    }

    public static CrashSection.Detail describeTrace(Throwable crash) {
        Set<String> frames = new LinkedHashSet<>();
        Set<String> nearThrow = new LinkedHashSet<>();
        List<String> messages = new ArrayList<>();
        ClassLoader game = GameReports.class.getClassLoader();
        for (Throwable at : Causes.list(crash)) {
            boolean near = true;
            for (StackTraceElement frame : at.getStackTrace()) {
                frames.add(frame.getClassName());
                if (near) {
                    nearThrow.add(frame.getClassName());
                    near = findModOf(frame.getClassName(), game) == null;
                }
            }
            messages.add(at.toString());
        }
        GameReports reports = current;
        String read = reports == null ? null : reports.findEmptiedRead(crash, game);
        boolean isNull = Causes.findInnermost(crash) instanceof NullPointerException;
        return describeTrace(frames, nearThrow, messages, read != null && isNull ? "Hotwire emptied " + read
                + ", and the code that threw reads it" : null, read != null && !isNull
                ? List.of("the code that threw reads " + read + ", which Hotwire emptied") : List.of());
    }

    public static void noteEmptied(String owner, String field) {
        GameReports reports = current;
        if (reports != null) {
            reports.emptied.add(owner + "." + field);
        }
    }

    public static void noteFilled(String owner, String field) {
        GameReports reports = current;
        if (reports != null) {
            reports.emptied.remove(owner + "." + field);
        }
    }

    private @Nullable String findEmptiedRead(Throwable crash, ClassLoader game) {
        if (this.emptied.isEmpty()) {
            return null;
        }
        for (Throwable at : Causes.list(crash)) {
            for (StackTraceElement frame : Arrays.stream(at.getStackTrace()).limit(NEAR_FRAMES).toList()) {
                String read = this.findEmptiedRead(frame, game);
                if (read != null) {
                    return read;
                }
            }
        }
        return null;
    }

    private @Nullable String findEmptiedRead(StackTraceElement frame, ClassLoader game) {
        try (InputStream in = game.getResourceAsStream(frame.getClassName().replace('.', '/') + ".class")) {
            if (in == null) {
                return null;
            }
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (MethodNode method : node.methods) {
                if (!method.name.equals(frame.getMethodName())) {
                    continue;
                }
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof FieldInsnNode field
                            && this.emptied.contains(field.owner + "." + field.name)) {
                        return field.owner.substring(field.owner.lastIndexOf('/') + 1) + "." + field.name;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
        }
        return null;
    }

    private static @Nullable String findModOf(String name, ClassLoader game) {
        try {
            return Owners.findModOf(Class.forName(name, false, game));
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    public static Map<String, String> listCrashDetails(Throwable crash) {
        Map<String, String> details = new LinkedHashMap<>();
        for (CrashSection.Detail detail : getCrashSection().details()) {
            details.put(CrashSection.TITLE + ": " + detail.name(), detail.value());
        }
        CrashSection.Detail trace = describeTrace(crash);
        details.put(CrashSection.TITLE + ": " + trace.name(), trace.value());

        if (CrashSection.isProven(trace.value())) {
            details.put("!!! If you are reading this, Hotwire caused this crash", trace.value());
        }
        return details;
    }

    public static void onCrashSaved(Path file, Throwable crash) {
        CrashSection.Detail trace = describeTrace(crash);
        boolean cleared = CrashSection.isCleared(trace.value());
        if (countRepeat(file, cleared)) {
            return;
        }
        logCrashDecision(trace);
        if (!cleared) {
            tellCrashSaved(file);
        }
    }

    public static CrashSection.Detail describeTrace(Collection<String> frames, Collection<String> nearThrow,
            Collection<String> messages) {
        return describeTrace(frames, nearThrow, messages, null, List.of());
    }

    private static CrashSection.Detail describeTrace(Collection<String> frames, Collection<String> nearThrow,
            Collection<String> messages, @Nullable String proof, List<String> ties) {
        GameReports reports = current;
        ActionJournal journal = ActionJournal.get();
        ActionJournal.CaughtCrash before = journal.findCaughtJustBefore();
        String first = before == null ? null : before.decision();
        if (reports == null) {
            return CrashBlame.decide(CrashSection.describeTrace(frames, nearThrow, messages, name -> null, Set.of(),
                    name -> null, id -> id), new CrashBlame.Session(null, proof, ties, journal.getLastChange(),
                    journal.countSecondsSinceChange(), journal.hasCaught(), first));
        }
        Set<String> switched = new HashSet<>(reports.off);
        switched.addAll(reports.added);
        ClassLoader game = GameReports.class.getClassLoader();
        reports.note(frames, messages, game);
        CrashSection.Detail trace = CrashSection.describeTrace(frames, nearThrow, messages, reports.rebuiltBy::get,
                switched, name -> findModOf(name, game), reports.nameOf);

        String now = crashOffUnderWay ? null : reports.underWay;
        CrashSection.Detail decided = CrashBlame.decide(trace, new CrashBlame.Session(now, proof, ties,
                journal.getLastChange(), journal.countSecondsSinceChange(), journal.hasCaught(), first));
        reports.lastDecision = decided.value();
        return decided;
    }

    private String describeSpan() {
        LocalTime now = LocalTime.now(ZoneId.systemDefault());
        LocalTime from = this.begun.equals(LocalTime.MIN) ? now.minusMinutes(1) : this.begun;
        return SPAN.format(from) + "-" + SPAN.format(now.plusSeconds(1));
    }

    public synchronized Report reportSwitch(ModSwitcher.Result result, Set<String> off, Report.OffCheck offCheck,
            Map<String, Map<String, Integer>> removed, Map<String, Map<String, Integer>> restored,
            List<Report.Failure> faults) {
        Report.Operation operation = result.plan().direction() == SwitchPlan.Direction.OFF ? Report.Operation.OFF
                : Report.Operation.ON;
        Map<String, String> names = new HashMap<>();
        result.plan().order().forEach(id -> names.put(id, this.nameOf.apply(id)));
        long millis = result instanceof ModSwitcher.Result.Done done ? done.millis() : 0;
        Report report = Report.of(operation, this.machine, names, result, removed, restored,
                List.of(new Report.Timing("switch", millis)), offCheck).withFaults(faults);
        if (result instanceof ModSwitcher.Result.Done done) {
            this.redefined.addAll(done.applied().rebuilt());
        }
        this.off = Set.copyOf(off);
        this.last = report;
        this.tell(report);
        this.write(report);
        return report;
    }

    public synchronized void onAdded(List<String> ids, ModSwitcher.Result switched, long millis) {
        this.added.addAll(ids);
        this.store(Report.of(Report.Operation.ADD, this.machine, this.getNames(ids), ids, getApplyResult(switched),
                List.of(new Report.Timing("add", millis)), switched instanceof ModSwitcher.Result.Failed failed
                        ? Report.Failure.of("switch on", null, failed.cause()) : null));
    }

    public synchronized void onAddFailed(String jar, Throwable cause, long millis) {
        this.store(Report.of(Report.Operation.ADD, this.machine, Map.of(), List.of(jar),
                new SwitchApplier.ApplyResult(List.of(), List.of(), List.of()),
                List.of(new Report.Timing("add", millis)), Report.Failure.of("add", null, cause)));
    }

    public synchronized void onUpdated(ModUpdater.Result result, long millis) {
        Report report = switch (result) {
            case ModUpdater.Result.Updated updated -> Report.of(Report.Operation.UPDATE, this.machine,
                    this.getNames(List.of(updated.id())), List.of(updated.id()), updated.applied(),
                    List.of(new Report.Timing("update", millis)), null);
            case ModUpdater.Result.Queued queued -> Report.of(Report.Operation.UPDATE, this.machine,
                    this.getNames(List.of(queued.id())), List.of(queued.id()),
                    new SwitchApplier.ApplyResult(List.of(), List.of(), List.of()),
                    List.of(new Report.Timing("queue", millis)), null);
            case ModUpdater.Result.Refused refused -> null;
            case ModUpdater.Result.Failed failed -> Report.of(Report.Operation.UPDATE, this.machine, Map.of(),
                    List.of(), new SwitchApplier.ApplyResult(List.of(), List.of(), List.of()),
                    List.of(new Report.Timing("update", millis)), Report.Failure.of("update", null, failed.cause()));
        };
        this.store(report);
    }

    private void store(@Nullable Report report) {
        if (report == null) {
            return;
        }

        List<Report.Failure> faults = new ArrayList<>(this.outerFaults);
        this.outerFaults.clear();
        faults.addAll(SwitchFaults.take());
        Report told = report.withFaults(List.copyOf(faults));
        this.redefined.addAll(told.rebuilt());
        this.last = told;
        this.tell(told);
        this.write(told);
    }

    private void tell(Report report) {
        if (report.plan().order().isEmpty()) {
            return;
        }
        List<String> order = report.plan().order();

        String asked = report.operation() == Report.Operation.ADD ? order.getLast() : order.getFirst();
        String name = report.nameOf(asked);
        String line = LocalTime.now(ZoneId.systemDefault()).withNano(0) + " " + report.operation().getWords() + " "
                + asked + (name.equals(asked) ? "" : " (" + name + ")");
        if (order.size() > 1) {
            line += ", " + (order.size() - 1) + (report.operation() == Report.Operation.ADD ? " nested" : " with it");
        }
        if (report.plan().isRefused()) {
            line += ": refused, nothing changed";
        } else if (!report.failures().isEmpty()) {
            line += ": failed, nothing changed";
        } else {
            line += ": " + report.rebuilt().size() + " classes redefined" + switch (report.offCheck()) {
                case Report.OffCheck.AllOff allOff -> "";
                case Report.OffCheck.PartlyOff partly -> ", partly off";
                case Report.OffCheck.NotRun notRun -> "";
            } + (report.faults().isEmpty() ? "" : ", with faults");
        }
        this.story.add(line);
        LastRebuild last = new LastRebuild(List.copyOf(order), System.nanoTime());
        String span = this.describeSpan();
        for (String rebuilt : report.rebuilt()) {
            this.rebuiltBy.putIfAbsent(rebuilt, line);
            this.lastRebuilt.put(rebuilt, last);
            this.ranWhen.put(rebuilt, span);
        }
    }

    public void onRebuilt(Collection<String> classes, String line) {
        this.story.add(line + ": " + classes.size() + " classes redefined");
        classes.forEach(rebuilt -> this.rebuiltBy.putIfAbsent(rebuilt, line));
        String span = this.describeSpan();
        classes.forEach(rebuilt -> this.ranWhen.put(rebuilt, span));
    }

    private Map<String, String> getNames(List<String> ids) {
        Map<String, String> names = new HashMap<>();
        ids.forEach(id -> names.put(id, this.nameOf.apply(id)));
        return names;
    }

    private static SwitchApplier.ApplyResult getApplyResult(ModSwitcher.Result switched) {
        return switched instanceof ModSwitcher.Result.Done done ? done.applied()
                : new SwitchApplier.ApplyResult(List.of(), List.of(), List.of());
    }

    Optional<Path> findLastSessionCrash() {
        Path game = FabricLoader.getInstance().getGameDir();
        Path marker = game.resolve("hotwire").resolve("last-session.txt");
        Optional<Path> crash = Optional.empty();
        try {
            if (Files.isRegularFile(marker)) {
                Instant last = Instant.parse(Files.readString(marker).strip());
                Set<String> reported = readReported(game);
                List<Path> found = new ArrayList<>(listWrittenAfter(game.resolve("crash-reports"), ".txt", last));
                found.addAll(listWrittenAfter(game, ".log", last).stream()
                        .filter(file -> file.getFileName().toString().startsWith("hs_err")).toList());
                crash = found.stream().filter(file -> !reported.contains(String.valueOf(file.getFileName())))
                        .filter(file -> {
                            try {
                                return Files.getLastModifiedTime(file).toInstant().isBefore(this.began);
                            } catch (IOException e) {
                                return false;
                            }
                        }).filter(GameReports::namesHotwire).reduce((first, second) -> second);

                Path freeze = game.resolve("hotwire").resolve("logs").resolve(FreezeDump.FILE);
                if (crash.isEmpty() && Files.isRegularFile(freeze)) {
                    Instant written = Files.getLastModifiedTime(freeze).toInstant();
                    Instant started = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
                    if (written.isAfter(last) && written.isBefore(started)) {
                        Path journal = game.resolve("hotwire").resolve("logs").resolve(ActionJournal.LAST);
                        if (FreezeBlame.isHotwires(read(freeze), Files.isRegularFile(journal) ? read(journal) : "")) {
                            crash = Optional.of(freeze);
                        } else {
                            LOGGER.info("[Hotwire] the game froze last session with no part of Hotwire's in it");
                        }
                    }
                }
            }
            AtomicFiles.writeString(marker, this.began.toString());

            Files.deleteIfExists(game.resolve("hotwire").resolve("reported-crashes.txt"));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[Hotwire] the last session's start could not be read or kept", e);
        }
        return crash;
    }

    private static boolean namesHotwire(Path file) {
        String text = read(file);
        if (file.getFileName().toString().startsWith("hs_err")) {
            return NativeBlame.of(NativeCrash.read(text), false).part() == NativeBlame.Part.HOTWIRE;
        }
        int at = text.indexOf("Hotwire involved? ");
        if (at >= 0) {
            return !CrashSection.isCleared(text.substring(at));
        }

        return text.contains("dev.moon.hotwire") || text.contains("dev/moon/hotwire")
                || text.contains("from mod hotwire");
    }

    private boolean isFromLastSession(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant().isBefore(this.began);
        } catch (IOException e) {
            return false;
        }
    }

    Path writeBundle(String title, String why, List<TidyLog.Mod> mods, List<Path> extra, String environment)
            throws IOException {
        synchronized (this.bundling) {
            return this.writeBundleNow(title, why, mods, extra, environment);
        }
    }

    private Path writeBundleNow(String title, String why, List<TidyLog.Mod> mods, List<Path> extra,
            String environment) throws IOException {
        this.onWritten();
        Path game = FabricLoader.getInstance().getGameDir();
        Map<String, String> texts = new LinkedHashMap<>();

        texts.put(Diagnosis.FILE, "");
        Path log = game.resolve("logs").resolve("latest.log");
        Instant now = Instant.now();
        Path logs = game.resolve("hotwire").resolve("logs");

        boolean froze = FreezeDump.isRecent(logs.resolve(FreezeDump.FILE));

        String told = froze ? read(logs.resolve(FreezeDump.FILE)).lines().limit(3).collect(Collectors.joining(" "))
                + " " + why : why;
        texts.put("latest.log", TidyLog.write(told, WHEN.format(now), this.details.describeMachine(), mods,
                Files.isRegularFile(log) ? getTail(read(log)) : "(the game wrote no log)\n"));
        HotwireLog.flush();

        boolean lastSession = froze || extra.stream().anyMatch(this::isFromLastSession);

        LastThrow about = lastSession ? null : this.lastThrow;
        if (about == null && !extra.isEmpty()) {
            String crash = read(extra.get(0));
            about = new LastThrow(Diagnosis.readMessages(crash), Diagnosis.readFrames(crash), List.of(), List.of());
        }
        Path whole = logs.resolve(lastSession ? HotwireLog.LAST : HotwireLog.FILE);

        ActionJournal.get().flush();
        Path journal = logs.resolve(lastSession ? ActionJournal.LAST : ActionJournal.FILE);

        String decision = lastSession || this.lastDecision.isEmpty() ? ""
                : "Whose crash\n  " + this.lastDecision + "\n\n";
        texts.put(Diagnosis.FILE, OutdatedNotice.note() + decision
                + (Files.isRegularFile(journal) ? Diagnosis.describeJournal(read(journal), lastSession ? 0
                        : ManagementFactory.getRuntimeMXBean().getStartTime()) : "")
                + Diagnosis.write(told, about == null ? List.of() : about.messages(),
                about == null ? List.of() : about.frames(), about == null ? List.of() : about.mods(),
                about == null ? List.of() : about.spans(), Files.isRegularFile(whole) ? read(whole) : ""));

        Path pending = game.resolve("hotwire").resolve("updates.json");
        if (Files.isRegularFile(pending)) {
            texts.put("hotwire-updates.json", read(pending));
        }

        Path swapped = game.resolve("hotwire").resolve("logs").resolve(SwapAtClose.LAST_LOGS);
        if (Files.isRegularFile(swapped)) {
            texts.put("hotwire-last-close.log", read(swapped));
        }

        Path worlds = game.resolve("hotwire").resolve(WorldContentRecord.History.FILE);
        if (Files.isRegularFile(worlds)) {
            List<String> lines = read(worlds).lines().toList();
            texts.put("hotwire-" + WorldContentRecord.History.FILE,
                    String.join("\n", lines.subList(Math.max(0, lines.size() - WORLD_LOG_LINES), lines.size())));
        }

        for (String name : List.of(HotwireLog.START_FAILURE, SwapAtClose.LAST_LOGS)) {
            if (Files.isRegularFile(logs.resolve(name))) {
                texts.put("hotwire-" + name, read(logs.resolve(name)));
            }
        }

        if (froze) {
            texts.put("hotwire-" + FreezeDump.FILE, read(logs.resolve(FreezeDump.FILE)));
        }
        if (FreezeDump.isRecent(logs.resolve(FreezeDump.PAUSE_FILE))) {
            texts.put("hotwire-" + FreezeDump.PAUSE_FILE, read(logs.resolve(FreezeDump.PAUSE_FILE)));
        }
        for (String name : lastSession ? List.of(HotwireLog.FILE, HotwireLog.LAST) : List.of(HotwireLog.FILE)) {
            if (Files.isRegularFile(logs.resolve(name))) {
                texts.put("hotwire-" + name, getTail(read(logs.resolve(name))));
            }
        }
        if (Files.isRegularFile(logs.resolve(HotwireLog.STARTUP))) {
            texts.put("hotwire-" + HotwireLog.STARTUP, read(logs.resolve(HotwireLog.STARTUP)));
        }
        ActionJournal.get().flush();
        for (String name : lastSession ? List.of(ActionJournal.FILE, ActionJournal.LAST)
                : List.of(ActionJournal.FILE)) {
            if (Files.isRegularFile(logs.resolve(name))) {
                texts.put("hotwire-" + name, read(logs.resolve(name)));
            }
        }
        texts.put("hotwire-environment.txt", environment);

        this.listSince(this.folder, ".txt").stream()
                .filter(file -> !String.valueOf(file.getFileName()).startsWith("Hotwire "))
                .forEach(file -> texts.put("reports/" + file.getFileName(), read(file)));
        this.listSince(game.resolve("crash-reports"), ".txt")
                .forEach(file -> texts.put("crash-reports/" + file.getFileName(), read(file)));
        extra.forEach(file -> texts.put("crash-reports/" + file.getFileName(), read(file)));
        this.listSince(game, ".log").stream().filter(file -> file.getFileName().toString().startsWith("hs_err"))
                .forEach(file -> texts.put("jvm-crashes/" + file.getFileName(), read(file)));
        Path zip = ReportBundle.write(this.folder, now, title, this.machine.hotwireVersion(), texts,
                Set.of(this.details.getPlayerName()));
        return zip;
    }

    private void note(Collection<String> frames, Collection<String> messages, ClassLoader game) {
        Set<String> mods = new TreeSet<>();
        Set<String> spans = new TreeSet<>();
        for (String frame : frames) {
            try {
                String mod = Owners.findModOf(Class.forName(frame, false, game));
                if (mod != null) {
                    mods.add(mod);
                }
            } catch (ClassNotFoundException | LinkageError e) {
            }
            String slashed = frame.replace('.', '/');
            String span = this.ranWhen.get(slashed);
            if (span != null) {
                spans.add(span);
            }
            LastRebuild last = this.lastRebuilt.get(slashed);
            if (last != null) {
                mods.addAll(last.ids());
            }
        }
        this.lastThrow = new LastThrow(List.copyOf(messages), List.copyOf(frames), List.copyOf(mods),
                List.copyOf(spans));
    }

    private List<Path> listSince(Path folder, String suffix) {
        return listWrittenAfter(folder, suffix, this.began);
    }

    private static List<Path> listWrittenAfter(Path folder, String suffix, Instant then) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> file.getFileName().toString().endsWith(suffix)).filter(file -> {
                try {
                    return Files.getLastModifiedTime(file).toInstant().isAfter(then);
                } catch (IOException e) {
                    return false;
                }
            }).sorted().toList();
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] could not list {} for the report", folder, e);
            return List.of();
        }
    }

    private static String read(Path file) {
        try (SeekableByteChannel in = Files.newByteChannel(file)) {
            long size = in.size();
            int length = (int) Math.min(size, MOST_READ);
            in.position(size - length);
            ByteBuffer bytes = ByteBuffer.allocate(length);
            while (bytes.hasRemaining()) {
                if (in.read(bytes) < 0) {
                    break;
                }
            }

            return new String(bytes.array(), 0, bytes.position(), StandardCharsets.UTF_8);
        } catch (IOException | UncheckedIOException e) {
            return "(could not be read: " + e + ")";
        }
    }

    private static String getTail(String log) {
        int retain = 4_000_000;
        return log.length() <= retain ? log : log.substring(log.length() - retain);
    }

    private final Object bundling = new Object();

    private void write(Report report) {
        Instant written = Instant.now();
        this.writer.execute(() -> {
            try {
                ReportWriter.write(report, written, this.folder);
            } catch (IOException e) {
                LOGGER.warn("[Hotwire] could not write the report to {}", this.folder, e);
            }
        });
    }

    private void onWritten() {
        try {
            this.writer.submit(() -> { }).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("[Hotwire] the reports still being written are left out of the bundle", e);
        }
    }

}
