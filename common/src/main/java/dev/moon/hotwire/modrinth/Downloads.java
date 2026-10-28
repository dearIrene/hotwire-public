package dev.moon.hotwire.modrinth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

public final class Downloads {
    private static final long SHOWN_AFTER_END_NANOS = 2_500_000_000L;

    public enum State {
        WAITING,
        GETTING,
        DONE,
        FAILED
    }

    public record Job(int id, String name, long done, long total, State state, long endedNanos) {
        public double getFraction() {
            return this.total <= 0 ? 0 : Math.min(1, this.done / (double) this.total);
        }

        public boolean isOver() {
            return this.state == State.DONE || this.state == State.FAILED;
        }
    }

    private final List<Job> jobs = new ArrayList<>();
    private int next;

    public synchronized int begin(String name, long total) {
        int id = ++this.next;
        this.jobs.add(new Job(id, name, 0, total, State.WAITING, 0));
        return id;
    }

    public synchronized void start(int id) {
        this.set(id, job -> new Job(id, job.name(), job.done(), job.total(), State.GETTING, 0));
    }

    public synchronized void advance(int id, long done) {
        this.set(id, job -> new Job(id, job.name(), done, job.total(), job.state(), 0));
    }

    public synchronized void end(int id, boolean ok) {
        this.set(id, job -> new Job(id, job.name(), ok ? job.total() : job.done(), job.total(),
                ok ? State.DONE : State.FAILED, System.nanoTime()));
    }

    private void set(int id, UnaryOperator<Job> change) {
        for (int at = 0; at < this.jobs.size(); at++) {
            if (this.jobs.get(at).id() == id) {
                this.jobs.set(at, change.apply(this.jobs.get(at)));
                return;
            }
        }
    }

    public synchronized List<Job> snapshot() {
        long now = System.nanoTime();
        this.jobs.removeIf(job -> job.isOver() && now - job.endedNanos() > SHOWN_AFTER_END_NANOS);
        return List.copyOf(this.jobs);
    }
}
