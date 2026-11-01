package dev.moon.hotwire.report;

import dev.moon.hotwire.switching.ModSwitcher;
import java.io.IOException;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ActionJournal {
    public static final String FILE = "journal.txt";
    public static final String LAST = "previous-journal.txt";

    static final String HEAD = "# Hotwire journal 1: nanoseconds since the game started, start/end/at, action, place,"
            + " target, outcome, detail";
    private static final Logger LOGGER = LoggerFactory.getLogger(ActionJournal.class);
    private static final Pattern BREAKS = Pattern.compile("[\\t\\r\\n]+");
    private static final long MS = 1_000_000L;

    public enum Action {
        TITLE, OPEN, JOIN, LEAVE, OFF, ON, ADD, UPDATE, REOPEN_OFF, REOPEN_ON, REOPEN_ADD, LATER_OFF, LATER_ADD,
        UNDO_LATER, SETTING, FILL, CRASH_OFF, REPORT
    }

    public enum Place { TITLE, WORLD, SERVER }

    public enum Outcome { DONE, REFUSED, FAILED, REVERTED, QUEUED }

    private static volatile ActionJournal current = new ActionJournal(null, () -> Place.TITLE, System::nanoTime, 0);

    private final @Nullable Writer out;
    private final Supplier<Place> place;
    private final LongSupplier clock;

    private final long start;
    private final LongAdder lines = new LongAdder();
    private final LongAdder writing = new LongAdder();
    private final LongAccumulator slowest = new LongAccumulator(Math::max, 0);
    private final LongAdder flushing = new LongAdder();

    private volatile @Nullable Executor flusher;
    private final AtomicBoolean flushQueued = new AtomicBoolean();

    private volatile long lastLine;

    private ActionJournal(@Nullable Writer out, Supplier<Place> place, LongSupplier clock, long start) {
        this.out = out;
        this.place = place;
        this.clock = clock;
        this.start = start;
    }

    public static ActionJournal get() {
        return current;
    }

    public static synchronized void open(Path folder, Supplier<Place> place) {
        if (current.out != null) {
            return;
        }
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        current = openIn(folder, place, System::nanoTime, System.nanoTime() - uptime * MS);
    }

    static ActionJournal openIn(Path folder, Supplier<Place> place, LongSupplier clock, long start) {
        try {
            Files.createDirectories(folder);
            Path file = folder.resolve(FILE);
            if (Files.exists(file)) {
                Files.move(file, folder.resolve(LAST), StandardCopyOption.REPLACE_EXISTING);
            }
            Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            ActionJournal journal = new ActionJournal(out, place, clock, start);
            append(out, HEAD + '\n');
            journal.flush();
            return journal;
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the journal could not be opened, so reports go without it", e);
            return new ActionJournal(null, place, clock, start);
        }
    }

    public Entry begin(Action action, String target) {
        this.write("start", action, this.place.get(), target, null, "");
        return new Entry(action, target);
    }

    public Entry begin(Action action, List<String> ids) {
        return this.begin(action, String.join("+", ids));
    }

    public void note(Action action, String target, Place where) {
        this.write("at", action, where, target, null, "");
    }

    public void note(Action action, String target) {
        this.note(action, target, this.place.get());
    }

    public long getLastLineAt() {
        return this.lastLine;
    }

    public void flushWith(Executor executor) {
        this.flusher = executor;
    }

    public void flush() {
        Writer to = this.out;
        if (to == null) {
            return;
        }
        long began = System.nanoTime();
        synchronized (to) {
            try {
                to.flush();
            } catch (IOException e) {
            }
        }
        this.flushing.add(System.nanoTime() - began);
    }

    public String describeCost() {
        return String.format(Locale.ROOT, "%d lines, %.3f ms in all where they were written (the slowest %.3f ms), and"
                + " %.3f ms putting them on disk, on Hotwire's reports thread once the engine started",
                this.lines.sum(), this.writing.sum() / (double) MS, this.slowest.get() / (double) MS,
                this.flushing.sum() / (double) MS);
    }

    private void write(String phase, Action action, Place where, String target, @Nullable Outcome outcome,
            String detail) {
        Writer to = this.out;
        if (to == null) {
            return;
        }
        long began = System.nanoTime();
        StringBuilder line = new StringBuilder(80).append('\t').append(phase).append('\t').append(toWord(action))
                .append('\t').append(where.name().toLowerCase(Locale.ROOT)).append('\t').append(clean(target));
        if (outcome != null) {
            line.append('\t').append(outcome.name().toLowerCase(Locale.ROOT));
            if (!detail.isEmpty()) {
                line.append('\t').append(clean(detail));
            }
        }
        line.append('\n');

        synchronized (to) {
            long now = this.clock.getAsLong();
            this.lastLine = now;
            append(to, (now - this.start) + line.toString());
        }
        this.flushSoon();
        long took = System.nanoTime() - began;
        this.lines.increment();
        this.writing.add(took);
        this.slowest.accumulate(took);
    }

    private void flushSoon() {
        Executor later = this.flusher;
        if (later == null) {
            this.flush();
            return;
        }
        if (this.flushQueued.compareAndSet(false, true)) {
            try {
                later.execute(() -> {
                    this.flushQueued.set(false);
                    this.flush();
                });
            } catch (RejectedExecutionException e) {
                this.flushQueued.set(false);
                this.flush();
            }
        }
    }

    private static void append(Writer to, String line) {
        try {
            to.append(line);
        } catch (IOException e) {
        }
    }

    static String toWord(Action action) {
        return action.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String clean(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\t' || c == '\r' || c == '\n') {
                return BREAKS.matcher(text).replaceAll(" ").strip();
            }
        }
        return text.strip();
    }

    static Outcome findOutcome(ModSwitcher.Result result) {
        return switch (result) {
            case ModSwitcher.Result.Done done -> Outcome.DONE;
            case ModSwitcher.Result.Refused refused -> Outcome.REFUSED;
            case ModSwitcher.Result.Failed failed -> failed.cause().reopenFixes() || failed.cause().isRestartOnly()
                    ? Outcome.REFUSED : Outcome.REVERTED;
        };
    }

    static String describe(ModSwitcher.Result result, boolean partlyOff) {
        if (result instanceof ModSwitcher.Result.Failed failed && failed.cause().reopenFixes()) {
            return "reopen";
        }
        if (result instanceof ModSwitcher.Result.Failed failed && failed.cause().isRestartOnly()) {
            return "restart";
        }
        if (!(result instanceof ModSwitcher.Result.Done done)) {
            return "";
        }
        String said = done.plan().order().size() > 1 ? "with " + String.join("+", done.plan().order()) : "";
        if (partlyOff) {
            said = (said + " partly off").strip();
        }
        if (!done.applied().leftBehind().isEmpty()) {
            said = (said + " left " + done.applied().leftBehind().size()).strip();
        }
        return said;
    }

    public final class Entry {
        private final Action action;
        private final String target;

        private Entry(Action action, String target) {
            this.action = action;
            this.target = target;
        }

        public void end(Outcome outcome) {
            this.end(outcome, "");
        }

        public void end(Outcome outcome, String detail) {
            ActionJournal.this.write("end", this.action, ActionJournal.this.place.get(), this.target, outcome, detail);
        }

        public void endSwitch(ModSwitcher.Result result, boolean partlyOff) {
            this.end(findOutcome(result), describe(result, partlyOff));
        }

        public void endThrown() {
            this.end(Outcome.FAILED, "thrown");
        }
    }
}
