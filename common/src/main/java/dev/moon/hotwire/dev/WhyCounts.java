package dev.moon.hotwire.dev;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

public final class WhyCounts {
    private static final int FEWEST = 3;

    private static final double NOISE = 2;

    private static final double LEAD = 1.25;
    private static final double MEGABYTE = 1024.0 * 1024.0;

    private final Map<Method, Count> counts = new HashMap<>();

    private List<Method> last = List.of();

    private record Method(String type, String name) {
    }

    private static final class Count {
        final long[] samples = new long[2];
        final long[] bytes = new long[2];

        final double[] squares = new double[2];
        final Map<Integer, Integer> lines = new HashMap<>();
    }

    public synchronized void add(int side, String type, String method, int line, boolean time, long bytes) {
        Count count = this.counts.computeIfAbsent(new Method(type, method), ignored -> new Count());
        count.samples[side] += time ? 1 : 0;
        count.bytes[side] += bytes;
        count.squares[side] += (double) bytes * bytes;
        count.lines.merge(line, 1, Integer::sum);
    }

    public synchronized List<DevWhy> read(double secondsA, double secondsB, double period, int most) {
        if (secondsA <= 0 || secondsB <= 0) {
            return List.of();
        }
        List<DevWhy> all = new ArrayList<>();
        this.counts.forEach((method, count) -> {
            double timeA = count.samples[0] * period / secondsA;
            double timeB = count.samples[1] * period / secondsB;
            double timeChance = Math.hypot(Math.sqrt((double) count.samples[0]) * period / secondsA,
                    Math.sqrt((double) count.samples[1]) * period / secondsB);
            boolean timed = (count.samples[0] >= FEWEST || count.samples[1] >= FEWEST)
                    && Math.abs(timeB - timeA) > NOISE * timeChance;
            double madeA = count.bytes[0] / MEGABYTE / secondsA;
            double madeB = count.bytes[1] / MEGABYTE / secondsB;
            double madeChance = Math.hypot(Math.sqrt(count.squares[0]) / MEGABYTE / secondsA,
                    Math.sqrt(count.squares[1]) / MEGABYTE / secondsB);
            boolean made = Math.abs(madeB - madeA) > NOISE * madeChance;
            if (timed || made) {
                int line = count.lines.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
                all.add(new DevWhy(method.type(), method.name(), line, timed ? timeA : 0, timed ? timeB : 0,
                        made ? madeA : 0, made ? madeB : 0));
            }
        });
        List<DevWhy> rows = new ArrayList<>(this.order(all, row -> Math.abs(row.timeB() - row.timeA()), most));
        List<DevWhy> rest = all.stream().filter(row -> !rows.contains(row)).toList();
        rows.addAll(this.order(rest, row -> Math.abs(row.madeB() - row.madeA()), most));
        this.last = rows.stream().map(row -> new Method(row.type(), row.method())).toList();
        return List.copyOf(rows);
    }

    private List<DevWhy> order(List<DevWhy> all, ToDoubleFunction<DevWhy> gap, int most) {
        List<DevWhy> rows = new ArrayList<>(all.stream().filter(row -> gap.applyAsDouble(row) > 0)
                .sorted(Comparator.comparingDouble((DevWhy row) -> -gap.applyAsDouble(row))
                        .thenComparing(DevWhy::findName)).toList());
        for (int at = 0; at + 1 < rows.size(); at++) {
            DevWhy upper = rows.get(at);
            DevWhy lower = rows.get(at + 1);
            int upperWas = this.last.indexOf(new Method(upper.type(), upper.method()));
            int lowerWas = this.last.indexOf(new Method(lower.type(), lower.method()));
            if (upperWas >= 0 && lowerWas >= 0 && lowerWas < upperWas
                    && gap.applyAsDouble(upper) < gap.applyAsDouble(lower) * LEAD) {
                Collections.swap(rows, at, at + 1);
            }
        }
        return rows.subList(0, Math.min(most, rows.size()));
    }
}
