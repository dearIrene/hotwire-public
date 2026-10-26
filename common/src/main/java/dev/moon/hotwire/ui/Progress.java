package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class Progress {
    private static final List<String> OFF_STEPS = List.of("hotwire.progress.planning",
            "hotwire.progress.switching_off", "hotwire.progress.reporting");
    private static final List<String> ON_STEPS = List.of("hotwire.progress.finding_log",
            "hotwire.progress.replaying_log", "hotwire.progress.reporting");
    private static final List<String> ADD_STEPS = List.of("hotwire.progress.reading", "hotwire.progress.letting_in",
            "hotwire.progress.hooking", "hotwire.progress.starting", "hotwire.progress.reloading");

    public record Snapshot(int run, String subject, List<String> labels, int at, String words, int countDone,
            int countTotal, boolean running, boolean goingOff, boolean adding, int mods, int modsDone) {
        public Snapshot {
            labels = List.copyOf(labels);
        }

        public String current() {
            return this.at >= 0 && this.at < this.labels.size() ? this.labels.get(this.at) : "";
        }

        public boolean hasCount() {
            return this.countTotal > 0;
        }

        public double getFraction() {
            int steps = Math.max(1, this.labels.size());
            double at = Math.max(0, Math.min(steps, this.at));
            double within = this.hasCount() && this.at < steps ? Math.min(1, this.countDone / (double) this.countTotal)
                    : 0;
            return Math.min(1, (at + within) / steps);
        }

        public String describe() {
            if (!this.words.isBlank()) {
                return this.words;
            }
            String current = this.current();
            return current.isBlank() ? Lang.get("hotwire.progress.preparing") : current;
        }
    }

    private volatile Snapshot now = new Snapshot(0, "", List.of(), 0, "", 0, 0, false, false, false, 0, 0);

    public Snapshot snapshot() {
        return this.now;
    }

    public boolean isBusy() {
        return this.now.running();
    }

    public void begin(String subject, boolean off, int mods) {
        if (this.isAdding()) {
            return;
        }
        this.now = new Snapshot(this.now.run() + 1, subject, read(off ? OFF_STEPS : ON_STEPS), 0, "", 0, 0, true, off,
                false, mods, 0);
    }

    public void beginAdding(String subject) {
        this.now = new Snapshot(this.now.run() + 1, subject, read(ADD_STEPS), 0, "", 0, 0, true, false, true, 1, 0);
    }

    public void stepAdding(int at) {
        Snapshot was = this.now;
        if (was.adding() && was.running()) {
            this.now = new Snapshot(was.run(), was.subject(), was.labels(), at, "", 0, 0, true, false, true, 1, 0);
        }
    }

    public void endAdding() {
        Snapshot was = this.now;
        this.now = new Snapshot(was.run(), was.subject(), was.labels(), was.labels().size(), "", 0, 0, false, false,
                false, was.mods(), was.modsDone());
    }

    private boolean isAdding() {
        return this.now.adding() && this.now.running();
    }

    public void step(int at, String subject) {
        if (this.isAdding()) {
            return;
        }
        Snapshot was = this.now;
        this.now = new Snapshot(was.run(), subject, was.labels(), at, "", 0, 0, true, was.goingOff(), was.adding(),
                was.mods(), was.modsDone());
    }

    void setPhase(String words) {
        if (this.isAdding()) {
            return;
        }
        Snapshot was = this.now;
        List<String> labels = new ArrayList<>(was.labels());
        labels.add(Math.min(was.at() + 1, labels.size()), words);
        this.now = new Snapshot(was.run(), was.subject(), labels, was.at() + 1, words, 0, 0, true, was.goingOff(),
                was.adding(), was.mods(), was.modsDone());
    }

    private static final String SAID = "say: ";
    private static final Map<String, String> AFTER = Map.of(
            "look ahead", "hotwire.progress.checking_world",
            "reopen check", "hotwire.progress.working_out",
            "mix", "hotwire.progress.changing",
            "field references: statics", "hotwire.progress.rebuilding",
            "registrations back", "hotwire.progress.rebuilding",
            "rebuild objects", "hotwire.progress.finishing",
            "start listeners", "hotwire.progress.reloading");

    public void showAfter(String part) {
        Snapshot was = this.now;
        String next = findNext(part, was.goingOff());
        if (was.running() && !was.adding() && next != null && !next.equals(was.words())) {
            this.setPhase(next);
        }
    }

    private static @Nullable String findNext(String part, boolean goingOff) {
        if (part.equals("redefine")) {
            return Lang.get(goingOff ? "hotwire.progress.taking_out" : "hotwire.progress.putting_back");
        }
        if (part.startsWith(SAID)) {
            return part.substring(SAID.length());
        }
        String next = AFTER.get(part);
        return next == null ? null : Lang.get(next);
    }

    private static List<String> read(List<String> keys) {
        return keys.stream().map(Lang::get).toList();
    }

    public void count(String words, int done, int total) {
        Snapshot was = this.now;
        this.now = new Snapshot(was.run(), was.subject(), was.labels(), was.at(), words, done, total, true,
                was.goingOff(), was.adding(), was.mods(), was.modsDone());
    }

    public void advance() {
        Snapshot was = this.now;
        this.now = new Snapshot(was.run(), was.subject(), was.labels(), was.at(), was.words(), was.countDone(),
                was.countTotal(), was.running(), was.goingOff(), was.adding(), was.mods(), was.modsDone() + 1);
    }

    public void end() {
        if (this.isAdding()) {
            return;
        }
        Snapshot was = this.now;
        this.now = new Snapshot(was.run(), was.subject(), was.labels(), was.labels().size(), "", 0, 0, false,
                was.goingOff(), was.adding(), was.mods(), was.modsDone());
    }
}
