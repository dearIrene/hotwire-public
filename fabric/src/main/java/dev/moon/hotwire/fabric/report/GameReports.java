package dev.moon.hotwire.fabric.report;

import dev.moon.hotwire.fabric.HotwireClient;
import dev.moon.hotwire.fabric.add.ModAdder;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.fabric.update.ModUpdater;
import dev.moon.hotwire.plan.SwitchPlan;
import dev.moon.hotwire.report.CrashSection;
import dev.moon.hotwire.report.Report;
import dev.moon.hotwire.report.ReportBundle;
import dev.moon.hotwire.report.ReportWriter;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.SwitchApplier;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import java.util.function.Function;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.launch.MixinBootstrap;

public final class GameReports {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameReports.class);
    private static volatile @Nullable GameReports current;

    private final Path folder;
    private final Report.Machine machine;
    private final Function<String, String> nameOf;

    private final Set<String> added = new CopyOnWriteArraySet<>();

    private final List<String> story = new CopyOnWriteArrayList<>();
    private final Map<String, String> rebuiltBy = new ConcurrentHashMap<>();
    private final Set<String> redefined = new ConcurrentSkipListSet<>();
    private volatile Set<String> off = Set.of();
    private volatile @Nullable Report last;

    private volatile @Nullable String underWay;

    private final AtomicInteger depth = new AtomicInteger();
    private final List<Report.Failure> outerFaults = new CopyOnWriteArrayList<>();
    private volatile @Nullable Path lastFile;

    private final ExecutorService writer = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("hotwire-reports").daemon().factory());
    private final Instant began = Instant.now();

    public GameReports(Function<String, String> nameOf) {
        FabricLoader loader = FabricLoader.getInstance();
        this.folder = loader.getGameDir().resolve("hotwire").resolve("reports");
        this.nameOf = nameOf;
        this.machine = new Report.Machine(getVersion(loader, HotwireClient.ID), getVersion(loader, "minecraft"),
                getVersion(loader, "fabricloader"), MixinBootstrap.VERSION, Runtime.version().toString(),
                System.getProperty("os.name", "") + " " + System.getProperty("os.version", ""),
                "launcher agent", Map.of());
        current = this;
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
        this.underWay = LocalTime.now(ZoneId.systemDefault()).withNano(0) + " " + operation.getWords() + " " + what
                + ": under way, the classes it redefined so far not counted below";
    }

    public void onEnded() {
        if (this.depth.decrementAndGet() <= 0) {
            this.depth.set(0);
            this.underWay = null;
        }
    }

    public static CrashSection.Detail describeTrace(Collection<String> frames, Collection<String> messages) {
        GameReports reports = current;
        if (reports == null) {
            return CrashSection.describeTrace(frames, messages, name -> null, Set.of(), name -> null, id -> id);
        }
        Set<String> switched = new HashSet<>(reports.off);
        switched.addAll(reports.added);
        ClassLoader game = GameReports.class.getClassLoader();
        return CrashSection.describeTrace(frames, messages, reports.rebuiltBy::get, switched, name -> {
            try {
                return GameRegistrations.findModOf(Class.forName(name, false, game));
            } catch (ClassNotFoundException | LinkageError e) {
                return null;
            }
        }, reports.nameOf);
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

    public synchronized void onAdded(ModAdder.Result result, long millis) {
        Report report = switch (result) {
            case ModAdder.Result.Added added -> {
                this.added.addAll(added.ids());
                yield Report.of(Report.Operation.ADD, this.machine, this.getNames(added.ids()), added.ids(),
                        getApplyResult(added.switched()), List.of(new Report.Timing("add", millis)),
                        added.switched() instanceof ModSwitcher.Result.Failed failed
                                ? Report.Failure.of("switch on", null, failed.cause()) : null);
            }
            case ModAdder.Result.Refused refused -> null;
            case ModAdder.Result.Failed failed -> Report.of(Report.Operation.ADD, this.machine, Map.of(), List.of(),
                    new SwitchApplier.ApplyResult(List.of(), List.of(), List.of()),
                    List.of(new Report.Timing("add", millis)), Report.Failure.of("add", null, failed.cause()));
        };
        this.store(report);
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
                case Report.OffCheck.AllOff allOff -> ", fully off";
                case Report.OffCheck.PartlyOff partly -> ", partly off";
                case Report.OffCheck.NotRun notRun -> "";
            } + (report.faults().isEmpty() ? "" : ", with faults");
        }
        this.story.add(line);
        for (String rebuilt : report.rebuilt()) {
            this.rebuiltBy.putIfAbsent(rebuilt, line);
        }
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
                crash = listWrittenAfter(game.resolve("crash-reports"), ".txt", last).stream().filter(file -> {
                    try {
                        return Files.getLastModifiedTime(file).toInstant().isBefore(this.began);
                    } catch (IOException e) {
                        return false;
                    }
                }).reduce((first, second) -> second);
            }
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, this.began.toString());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[Hotwire] the last session's start could not be read or kept", e);
        }
        return crash;
    }

    synchronized Path writeBundle(String why, String mods) throws IOException {
        return this.writeBundle(why, mods, List.of());
    }

    synchronized Path writeBundle(String why, String mods, List<Path> extra) throws IOException {
        this.onWritten();
        Path game = FabricLoader.getInstance().getGameDir();
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put("reason.txt", why + System.lineSeparator());
        texts.put("mods.txt", mods);
        this.listSince(this.folder, ".txt").forEach(file -> texts.put("reports/" + file.getFileName(), read(file)));
        Path log = game.resolve("logs").resolve("latest.log");
        if (Files.isRegularFile(log)) {
            texts.put("latest.log", getTail(read(log)));
        }
        this.listSince(game.resolve("crash-reports"), ".txt")
                .forEach(file -> texts.put("crash-reports/" + file.getFileName(), read(file)));
        extra.forEach(file -> texts.put("crash-reports/" + file.getFileName(), read(file)));
        this.listSince(game, ".log").stream().filter(file -> file.getFileName().toString().startsWith("hs_err"))
                .forEach(file -> texts.put("jvm-crashes/" + file.getFileName(), read(file)));
        Path zip = ReportBundle.write(this.folder, Instant.now(), texts, Set.of(Minecraft.getInstance().getUser()
                .getName()));
        this.lastFile = zip;
        return zip;
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
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException | UncheckedIOException e) {
            return "(could not be read: " + e + ")";
        }
    }

    private static String getTail(String log) {
        int retain = 4_000_000;
        return log.length() <= retain ? log : log.substring(log.length() - retain);
    }

    @Nullable Path findLastFile() {
        return this.lastFile;
    }

    private void write(Report report) {
        Instant written = Instant.now();
        this.writer.execute(() -> {
            try {
                this.lastFile = ReportWriter.write(report, written, this.folder);
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

    private static String getVersion(FabricLoader loader, String id) {
        return loader.getModContainer(id).map(ModContainer::getMetadata).map(m -> m.getVersion().getFriendlyString())
                .orElse("unknown");
    }

    List<String> getAddedIds() {
        return new ArrayList<>(this.added);
    }
}
