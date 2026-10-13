package dev.moon.hotwire.report;

import dev.moon.hotwire.config.AtomicFiles;
import dev.moon.hotwire.plan.SwitchPlan.EnabledNestedMod;
import dev.moon.hotwire.plan.SwitchPlan.Reason;
import dev.moon.hotwire.plan.SwitchPlan.Refusal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class ReportWriter {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    static final int MAX_REPORTS = 100;
    private static final int TIME_LENGTH = "yyyyMMdd-HHmmss".length();
    private static final Pattern REPORT = Pattern.compile("hotwire-[a-z_]+-\\d{8}-\\d{6}\\.txt");

    private final StringBuilder out = new StringBuilder();
    private final Report report;

    private ReportWriter(Report report) {
        this.report = report;
    }

    public static String render(Report report, Instant written) {
        return new ReportWriter(report).writeBody(written);
    }

    public static Path write(Report report, Instant written, Path folder) throws IOException {
        Files.createDirectories(folder);
        Path file = folder.resolve("hotwire-" + report.operation().name().toLowerCase(Locale.ROOT) + "-"
                + FILE_TIME.format(written) + ".txt");
        AtomicFiles.writeString(file, render(report, written));
        prune(folder);
        return file;
    }

    private static void prune(Path folder) throws IOException {
        List<Path> reports;
        try (Stream<Path> files = Files.list(folder)) {
            reports = files.filter(file -> REPORT.matcher(file.getFileName().toString()).matches())
                    .sorted(Comparator.comparing((Path file) -> {
                        String name = file.getFileName().toString();
                        return name.substring(name.length() - ".txt".length() - TIME_LENGTH);
                    }).reversed()).toList();
        }
        for (Path old : reports.subList(Math.min(MAX_REPORTS, reports.size()), reports.size())) {
            Files.deleteIfExists(old);
        }
    }

    private String writeBody(Instant written) {
        this.writeLine(0, "Hotwire report");
        this.writeLine(0, "Written: " + DateTimeFormatter.ISO_INSTANT.format(written));
        this.writeLine(0, "Operation: " + this.report.operation().getWords() + " "
                + this.joinNames(this.report.plan().order()));
        this.writeLine(0, "Result: " + this.describeResult());
        this.writeMachine();
        this.plan();
        this.list("Classes rebuilt", this.report.rebuilt());
        this.list("Classes not loaded yet", this.report.notLoaded());
        this.list("Mixins left behind", this.report.leftBehind());
        this.writeRegistrations("Registrations removed", this.report.removed());
        this.writeRegistrations("Registrations restored", this.report.restored());
        this.writeTimings();
        this.writeOffCheck();
        this.writeFailures("Failures", this.report.failures());
        if (!this.report.faults().isEmpty()) {
            this.writeFailures("Faults", this.report.faults());
        }
        return this.out.toString();
    }

    private String describeResult() {
        if (this.report.plan().isRefused()) {
            return "refused, nothing changed";
        }
        if (!this.report.failures().isEmpty()) {
            return "failed";
        }
        String done = this.report.offCheck() instanceof Report.OffCheck.PartlyOff ? "partly off" : "done";
        int faults = this.report.faults().size();
        return faults == 0 ? done : done + ", with " + faults + (faults == 1 ? " fault" : " faults");
    }

    private void writeMachine() {
        Report.Machine machine = this.report.machine();
        this.writeSection("Machine");
        this.writeLine(1, "Hotwire: " + machine.hotwireVersion());
        this.writeLine(1, "Minecraft: " + machine.minecraftVersion());
        this.writeLine(1, "Fabric Loader: " + machine.loaderVersion());
        this.writeLine(1, "Mixin: " + machine.mixinVersion());
        this.writeLine(1, "Java: " + machine.javaVersion());
        this.writeLine(1, "OS: " + machine.os());
        this.writeLine(1, "JVM route: " + machine.jvmRoute());
        this.writeSection("Settings");
        new TreeMap<>(machine.settings()).forEach((key, value) -> this.writeLine(1, key + ": " + value));
    }

    private void plan() {
        this.writeSection("Plan");
        this.report.plan().order().forEach(id -> this.writeLine(1, this.report.nameOf(id) + ": "
                + this.describeReason(Objects.requireNonNull(this.report.plan().why().get(id)))));
        for (EnabledNestedMod retained : this.report.plan().enabledNested()) {
            this.writeLine(1, "Kept " + this.report.nameOf(retained.mod()) + " inside "
                    + this.report.nameOf(retained.container())
                    + ": " + retained.why().name().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        this.report.plan().refusals()
                .forEach(refusal -> this.writeLine(1, "Refused: " + this.describeRefusal(refusal)));
    }

    private String describeReason(Reason reason) {
        return switch (reason) {
            case Reason.Asked asked -> "asked for";
            case Reason.DependsOn depends -> "depends on " + this.report.nameOf(depends.mod());
            case Reason.PatchesClassOf patches -> "patches " + patches.className() + " of "
                    + this.report.nameOf(patches.mod());
            case Reason.NestedIn nested -> "nested in " + this.report.nameOf(nested.container());
            case Reason.Ships ships -> "ships " + this.report.nameOf(ships.nested()) + " in its own jar";
            case Reason.NeededBy needed -> "needed by " + this.report.nameOf(needed.mod());
        };
    }

    private String describeRefusal(Refusal refusal) {
        return switch (refusal) {
            case Refusal.NotInstalled missing -> missing.mod() + " is not installed";
            case Refusal.Protected retained -> this.report.nameOf(retained.mod()) + " is needed by the game";
            case Refusal.Breaks breaks -> this.report.nameOf(breaks.mod()) + " breaks "
                    + this.report.nameOf(breaks.other());
            case Refusal.MissingDependency missing -> this.report.nameOf(missing.mod()) + " needs "
                    + missing.dependency() + ", which is not installed";
        };
    }

    private void list(String title, List<String> items) {
        this.writeSection(title + " (" + items.size() + ")");
        items.stream().sorted().forEach(item -> this.writeLine(1, item));
    }

    private void writeRegistrations(String title, Map<String, Map<String, Integer>> byMod) {
        this.writeSection(title);
        new TreeMap<>(byMod).forEach((mod, kinds) -> {
            StringBuilder counts = new StringBuilder();
            new TreeMap<>(kinds).forEach((kind, count) -> counts.append(counts.isEmpty() ? "" : ", ")
                    .append(kind).append(' ').append(count));
            this.writeLine(1, this.report.nameOf(mod) + ": " + counts);
        });
    }

    private void writeTimings() {
        this.writeSection("Timings");
        this.report.timings().forEach(timing -> this.writeLine(1, timing.step() + ": " + timing.millis() + " ms"));
    }

    private void writeOffCheck() {
        switch (this.report.offCheck()) {
            case Report.OffCheck.NotRun notRun -> this.writeSection("Off check: not run");
            case Report.OffCheck.AllOff allOff -> this.writeSection("Off check: passed");
            case Report.OffCheck.PartlyOff partly -> {
                this.writeSection("Off check: partly off");
                new TreeMap<>(partly.stillLive()).forEach((mod, live) -> live.stream().sorted()
                        .forEach(what -> this.writeLine(1, this.report.nameOf(mod) + ": " + what)));
            }
        }
    }

    private void writeFailures(String title, List<Report.Failure> failures) {
        this.writeSection(title + " (" + failures.size() + ")");
        for (Report.Failure failure : failures) {
            String mod = failure.mod();
            this.writeLine(1, "While " + failure.step() + (mod == null ? "" : " for " + this.report.nameOf(mod)));
            String prefix = "";
            for (Report.Cause cause : failure.causes()) {
                String message = cause.message();
                this.writeLine(2, prefix + cause.type() + (message == null ? "" : ": " + message));
                cause.frames().forEach(frame -> this.writeLine(3, "at " + frame));
                prefix = "Caused by: ";
            }
        }
    }

    private String joinNames(List<String> ids) {
        return ids.isEmpty() ? "nothing" : String.join(", ", ids.stream().map(this.report::nameOf).toList());
    }

    private void writeSection(String title) {
        this.out.append('\n');
        this.writeLine(0, title);
    }

    private void writeLine(int depth, String text) {
        this.out.append("  ".repeat(depth)).append(text).append('\n');
    }
}
