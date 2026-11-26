package dev.moon.hotwire.modrinth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

public final class Downloads {
    private static final long SHOWN_AFTER_END_NANOS = 2_500_000_000L;

    public enum State {
        WAITING,
        GETTING,
        PAUSED,
        DONE,
        FAILED,
        STOPPED
    }

    public static final class DownloadStop extends IOException {
        private static final long serialVersionUID = 1L;

        DownloadStop() {
            super("the download was stopped");
        }
    }

    public interface Feed extends LongConsumer {
        void awaitGo() throws IOException;

        boolean isPaused();
    }

    private static final long PAUSE_LOOK_MILLIS = 100;

    private static final AtomicInteger NEXT = new AtomicInteger();

    private static final Set<Downloads> QUEUES = ConcurrentHashMap.newKeySet();

    private final class Control implements Feed {
        private final int id;
        private volatile boolean paused;
        private volatile boolean stopped;

        Control(int id) {
            this.id = id;
        }

        @Override
        public void accept(long done) {
            Downloads.this.advance(this.id, done);
        }

        @Override
        public boolean isPaused() {
            return this.paused || this.stopped;
        }

        @Override
        public void awaitGo() throws IOException {
            try {
                while (this.paused && !this.stopped) {
                    Thread.sleep(PAUSE_LOOK_MILLIS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            if (this.stopped) {
                throw new DownloadStop();
            }
        }

        void pause(boolean pause) {
            this.paused = pause;
            Downloads.this.setPaused(this.id, pause);
        }

        Job find() {
            return Downloads.this.findJob(this.id);
        }
    }

    private static @Nullable Control findControl(int id) {
        for (Downloads queue : QUEUES) {
            Control control = queue.controls.get(id);
            if (control != null) {
                return control;
            }
        }
        return null;
    }

    private static List<Control> findAllControls() {
        return QUEUES.stream().flatMap(queue -> queue.controls.values().stream()).toList();
    }

    public static boolean pause(int id) {
        Control control = findControl(id);
        if (control != null) {
            control.pause(true);
        }
        return control != null;
    }

    public static boolean resume(int id) {
        Control control = findControl(id);
        if (control != null) {
            control.pause(false);
        }
        return control != null;
    }

    public static boolean stop(int id) {
        Control control = findControl(id);
        if (control != null) {
            control.stopped = true;
        }
        return control != null;
    }

    public static void pauseAll() {
        findAllControls().forEach(control -> control.pause(true));
    }

    public static void resumeAll() {
        findAllControls().forEach(control -> control.pause(false));
    }

    public static void stopAll() {
        findAllControls().forEach(control -> control.stopped = true);
    }

    public static boolean isAnyPaused() {
        return findAllControls().stream().anyMatch(control -> control.paused);
    }

    public static List<Job> findAllUnderWay() {
        return findAllControls().stream().map(Control::find).toList();
    }

    public Feed findFeed(int id) {
        Control control = this.controls.get(id);
        return control != null ? control : new Control(id);
    }

    public record Job(int id, String name, long done, long total, State state, long endedNanos) {
        public double getFraction() {
            return this.total <= 0 ? 0 : Math.min(1, this.done / (double) this.total);
        }

        public boolean isOver() {
            return this.state == State.DONE || this.state == State.FAILED || this.state == State.STOPPED;
        }
    }

    private static volatile @Nullable Consumer<Job> whenChanged;
    private static final AtomicInteger QUIET_NEXT =
            new AtomicInteger();

    private static final ThreadLocal<@Nullable Marks> MARKS = new ThreadLocal<>();
    private static final Marks UNMARKED = new Marks("", false);

    public record Marks(String tag, boolean quiet) {
        public Runnable carry(Runnable work) {
            return () -> {
                Runnable end = mark(this);
                try {
                    work.run();
                } finally {
                    end.run();
                }
            };
        }

        public <T> Callable<T> carry(Callable<T> work) {
            return () -> {
                Runnable end = mark(this);
                try {
                    return work.call();
                } finally {
                    end.run();
                }
            };
        }

        public Marks asQuiet() {
            return new Marks(this.tag, true);
        }
    }

    public static Marks findMarks() {
        Marks now = MARKS.get();
        return now == null ? UNMARKED : now;
    }

    private static Runnable mark(Marks marks) {
        Marks before = MARKS.get();
        Thread owner = Thread.currentThread();
        MARKS.set(marks);
        return () -> {
            if (Thread.currentThread().equals(owner)) {
                if (before == null) {
                    MARKS.remove();
                } else {
                    MARKS.set(before);
                }
            }
        };
    }

    private final List<Job> jobs = new ArrayList<>();

    private final Map<Integer, Control> controls = new ConcurrentHashMap<>();

    private final Set<Integer> quietJobs = ConcurrentHashMap.newKeySet();
    private final Map<Integer, String> tags = new ConcurrentHashMap<>();

    public static void onEveryChange(Consumer<Job> told) {
        whenChanged = told;
    }

    public static Runnable beginQuiet() {
        return mark(findMarks().asQuiet());
    }

    public static Runnable beginTag(String tag) {
        return mark(new Marks(tag, findMarks().quiet()));
    }

    public static String findTag(int id) {
        for (Downloads queue : QUEUES) {
            String tag = queue.tags.get(id);
            if (tag != null) {
                return tag;
            }
        }
        return "";
    }

    public static void markNextQuiet() {
        QUIET_NEXT.incrementAndGet();
    }

    private static boolean takeQuiet() {
        return findMarks().quiet() || QUIET_NEXT.getAndUpdate(left -> Math.max(0, left - 1)) > 0;
    }

    public synchronized int begin(String name, long total) {
        QUEUES.add(this);
        int id = NEXT.incrementAndGet();
        if (takeQuiet()) {
            this.quietJobs.add(id);
        }
        if (!findMarks().tag().isEmpty()) {
            this.tags.put(id, findMarks().tag());
        }
        Job job = new Job(id, name, 0, total, State.WAITING, 0);
        this.jobs.add(job);
        this.controls.put(id, new Control(id));
        this.tell(job);
        return id;
    }

    public synchronized void start(int id) {
        Control control = this.controls.get(id);
        State state = control != null && control.paused ? State.PAUSED : State.GETTING;
        this.set(id, job -> new Job(id, job.name(), job.done(), job.total(), state, 0));
    }

    private synchronized void setPaused(int id, boolean paused) {
        this.set(id, job -> job.isOver() ? job : new Job(id, job.name(), job.done(), job.total(),
                paused ? State.PAUSED : State.GETTING, 0));
    }

    private synchronized Job findJob(int id) {
        return this.jobs.stream().filter(job -> job.id() == id).findFirst()
                .orElseGet(() -> new Job(id, "", 0, 0, State.DONE, 0));
    }

    public synchronized void advance(int id, long done) {
        for (int at = 0; at < this.jobs.size(); at++) {
            Job job = this.jobs.get(at);
            if (job.id() == id) {
                if (job.total() <= 0 || done * 100 / job.total() != job.done() * 100 / job.total()) {
                    Job changed = new Job(id, job.name(), done, job.total(), job.state(), 0);
                    this.jobs.set(at, changed);
                    this.tell(changed);
                }
                return;
            }
        }
    }

    public synchronized void end(int id, boolean ok) {
        Control control = this.controls.remove(id);
        State failed = control != null && control.stopped ? State.STOPPED : State.FAILED;
        this.set(id, job -> new Job(id, job.name(), ok ? job.total() : job.done(), job.total(),
                ok ? State.DONE : failed, System.nanoTime()));
    }

    private void set(int id, UnaryOperator<Job> change) {
        for (int at = 0; at < this.jobs.size(); at++) {
            if (this.jobs.get(at).id() == id) {
                Job changed = change.apply(this.jobs.get(at));
                this.jobs.set(at, changed);
                this.tell(changed);
                return;
            }
        }
    }

    private void tell(Job job) {
        Consumer<Job> told = whenChanged;
        if (told != null && !this.quietJobs.contains(job.id())) {
            told.accept(job);
        }
    }

    public static List<Job> snapshotAll() {
        return QUEUES.stream().flatMap(queue -> queue.snapshot().stream())
                .sorted(Comparator.comparingInt(Job::id)).toList();
    }

    public synchronized List<Job> snapshot() {
        long now = System.nanoTime();
        this.jobs.removeIf(job -> {
            boolean gone = job.isOver() && now - job.endedNanos() > SHOWN_AFTER_END_NANOS;
            if (gone) {
                this.tags.remove(job.id());
                this.quietJobs.remove(job.id());
            }
            return gone;
        });
        return List.copyOf(this.jobs);
    }
}
