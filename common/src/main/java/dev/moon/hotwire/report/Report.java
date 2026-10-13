package dev.moon.hotwire.report;

import dev.moon.hotwire.plan.SwitchPlan;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.SwitchApplier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public record Report(Operation operation, Machine machine, Map<String, String> names, SwitchPlan plan,
        List<String> rebuilt, List<String> notLoaded, List<String> leftBehind,
        Map<String, Map<String, Integer>> removed, Map<String, Map<String, Integer>> restored, List<Timing> timings,
        OffCheck offCheck, List<Failure> failures, List<Failure> faults) {
    public Report {
        names = Map.copyOf(names);
        rebuilt = List.copyOf(rebuilt);
        notLoaded = List.copyOf(notLoaded);
        leftBehind = List.copyOf(leftBehind);
        removed = copy(removed);
        restored = copy(restored);
        timings = List.copyOf(timings);
        failures = List.copyOf(failures);
        faults = List.copyOf(faults);
    }

    public enum Operation {
        OFF("turn off"), ON("turn on"), DRY_RUN("dry run"), ADD("add"), UPDATE("update");

        private final String words;

        Operation(String words) {
            this.words = words;
        }

        public String getWords() {
            return this.words;
        }
    }

    public record Machine(String hotwireVersion, String minecraftVersion, String loaderVersion, String mixinVersion,
            String javaVersion, String os, String jvmRoute, Map<String, String> settings) {
        public Machine {
            settings = Map.copyOf(settings);
        }
    }

    public record Timing(String step, long millis) {
    }

    public sealed interface OffCheck {
        record NotRun() implements OffCheck {
        }

        record AllOff() implements OffCheck {
        }

        record PartlyOff(Map<String, List<String>> stillLive) implements OffCheck {
            public PartlyOff {
                stillLive = Map.copyOf(stillLive);
            }
        }
    }

    public record Cause(String type, @Nullable String message, List<String> frames) {
        public Cause {
            frames = List.copyOf(frames);
        }
    }

    public record Failure(String step, @Nullable String mod, List<Cause> causes) {
        public Failure {
            causes = List.copyOf(causes);
        }

        public static Failure of(String step, @Nullable String mod, Throwable thrown) {
            List<Cause> causes = new ArrayList<>();
            Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Throwable at = thrown; at != null && seen.add(at); at = at.getCause()) {
                List<String> frames = new ArrayList<>();
                for (StackTraceElement frame : at.getStackTrace()) {
                    frames.add(frame.toString());
                }
                causes.add(new Cause(at.getClass().getName(), at.getMessage(), frames));
            }
            return new Failure(step, mod, causes);
        }
    }

    public static Report of(Operation operation, Machine machine, Map<String, String> names, ModSwitcher.Result result,
            Map<String, Map<String, Integer>> removed, Map<String, Map<String, Integer>> restored,
            List<Timing> timings, OffCheck offCheck) {
        SwitchApplier.ApplyResult applied = result instanceof ModSwitcher.Result.Done done ? done.applied()
                : new SwitchApplier.ApplyResult(List.of(), List.of(), List.of());
        List<Failure> failures = result instanceof ModSwitcher.Result.Failed failed
                ? List.of(Failure.of("rebuild", null, failed.cause())) : List.of();
        return new Report(operation, machine, names, result.plan(), applied.rebuilt(), applied.notLoaded(),
                applied.leftBehind(), removed, restored, timings, offCheck, failures, List.of());
    }

    public Report withFaults(List<Failure> more) {
        return new Report(this.operation, this.machine, this.names, this.plan, this.rebuilt, this.notLoaded,
                this.leftBehind, this.removed, this.restored, this.timings, this.offCheck, this.failures, more);
    }

    public static Report of(Operation operation, Machine machine, Map<String, String> names, List<String> ids,
            SwitchApplier.ApplyResult applied, List<Timing> timings, @Nullable Failure failure) {
        Map<String, SwitchPlan.Reason> why = new HashMap<>();
        ids.forEach(id -> why.put(id, new SwitchPlan.Reason.Asked()));
        SwitchPlan plan = new SwitchPlan(SwitchPlan.Direction.ON, ids, why, List.of(), List.of());
        return new Report(operation, machine, names, plan, applied.rebuilt(), applied.notLoaded(),
                applied.leftBehind(), Map.of(), Map.of(), timings, new OffCheck.NotRun(),
                failure == null ? List.of() : List.of(failure), List.of());
    }

    public String nameOf(String id) {
        return this.names.getOrDefault(id, id);
    }

    private static Map<String, Map<String, Integer>> copy(Map<String, Map<String, Integer>> byMod) {
        Map<String, Map<String, Integer>> copy = new HashMap<>();
        byMod.forEach((mod, kinds) -> copy.put(mod, Map.copyOf(kinds)));
        return Map.copyOf(copy);
    }
}
