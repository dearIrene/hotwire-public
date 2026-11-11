package dev.moon.hotwire.fabric.dev;

import com.sun.management.ThreadMXBean;
import dev.moon.hotwire.dev.DevWhy;
import dev.moon.hotwire.dev.WhyCounts;
import dev.moon.hotwire.dev.WhyFrames;
import dev.moon.hotwire.dev.WhySides;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingStream;
import org.jspecify.annotations.Nullable;

final class CompareSampler implements AutoCloseable {
    private static final Duration PERIOD = Duration.ofMillis(10);
    private static final String TIME = "jdk.ExecutionSample";
    private static final String MEMORY = "jdk.ObjectAllocationSample";

    private static final int MOST = 6;
    private static final String WEIGHT = "weight";

    private static final long MARK_NANOS = TimeUnit.MILLISECONDS.toNanos(300);

    private final RecordingStream stream = new RecordingStream();
    private final WhyCounts counts = new WhyCounts();
    private final WhySides sides;
    private final Predicate<String> own;
    private final BiFunction<String, String, @Nullable String> mixin;

    private final Map<String, Map<String, Optional<String>>> merged = new ConcurrentHashMap<>();

    private volatile @Nullable Thread reader;
    private volatile boolean closed;

    CompareSampler(WhySides sides, Predicate<String> own, BiFunction<String, String, @Nullable String> mixin) {
        this.sides = sides;
        this.own = own;
        this.mixin = mixin;
        try {
            this.stream.enable(TIME).withPeriod(PERIOD);
            this.stream.enable(MEMORY).with("throttle", "200/s");

            this.stream.setMaxAge(Duration.ofSeconds(30));
            this.stream.onEvent(TIME, event -> this.add(event, true));
            this.stream.onEvent(MEMORY, event -> this.add(event, false));
            this.stream.startAsync();
        } catch (RuntimeException | LinkageError e) {
            this.stream.close();
            throw e;
        }
    }

    private void add(RecordedEvent event, boolean time) {
        this.reader = Thread.currentThread();
        long at = event.getStartTime().toEpochMilli();

        if (!this.closed && !this.sides.reaches(at)) {
            LockSupport.parkNanos(MARK_NANOS);
        }
        int side = this.sides.find(at);
        RecordedStackTrace trace = event.getStackTrace();
        if (side < 0 || trace == null) {
            return;
        }
        List<WhyFrames.Frame> frames = new ArrayList<>();
        for (RecordedFrame frame : trace.getFrames()) {
            if (frame.isJavaFrame()) {
                frames.add(new WhyFrames.Frame(frame.getMethod().getType().getName(), frame.getMethod().getName(),
                        frame.getLineNumber()));
            }
        }
        WhyFrames.Frame place = WhyFrames.find(frames, this.own, this::findMixin);
        if (place != null) {
            this.counts.add(side, place.type(), place.method(), place.line(), time,
                    time || !event.hasField(WEIGHT) ? 0 : event.getLong(WEIGHT));
        }
    }

    private @Nullable String findMixin(String type, String method) {
        if (type.startsWith("java.") || type.startsWith("jdk.") || type.startsWith("sun.")) {
            return null;
        }
        return this.merged.computeIfAbsent(type, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(method, ignored -> Optional.ofNullable(this.mixin.apply(type, method)))
                .orElse(null);
    }

    void forget() {
        this.merged.clear();
    }

    List<DevWhy> read(double secondsA, double secondsB) {
        return this.counts.read(secondsA, secondsB, PERIOD.toMillis() / 1000.0, MOST);
    }

    long findMade(ThreadMXBean threads) {
        Thread from = this.reader;
        return from == null ? 0 : Math.max(0, threads.getThreadAllocatedBytes(from.threadId()));
    }

    @Override
    public void close() {
        this.closed = true;
        this.stream.close();
    }
}
