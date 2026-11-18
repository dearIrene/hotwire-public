package dev.moon.hotwire.ui.notices;

import dev.moon.hotwire.modrinth.Downloads;
import dev.moon.hotwire.ui.Lang;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DownloadNotices {
    private static final int ROOM = 3;

    private static final int PAUSE_MARK = 1000;

    private final Map<Integer, Integer> holding = new HashMap<>();
    private final Set<Integer> gone = new HashSet<>();
    private static volatile boolean quiet;

    public static void setQuiet(boolean shownElsewhere) {
        quiet = shownElsewhere;
    }

    public synchronized void show(Downloads.Job job) {
        String key = "download " + job.id();
        switch (job.state()) {
            case WAITING -> {
            }
            case GETTING, PAUSED -> {
                if (!quiet) {
                    this.showGetting(job, key);
                }
            }
            case STOPPED -> {
                boolean inHand = this.holding.remove(job.id()) != null;
                this.gone.remove(job.id());
                if (inHand) {
                    Notices.show(new Notice(Notice.Kind.ASK, job.name(), Lang.get("hotwire.notice.download_stopped"),
                            key));
                }
            }
            case DONE -> {
                boolean inHand = this.holding.remove(job.id()) != null;
                this.gone.remove(job.id());
                if (inHand && !quiet) {
                    Notices.show(new Notice(Notice.Kind.DONE, job.name(), Lang.get("hotwire.notice.downloaded"),
                            key));
                }
            }
            case FAILED -> {
                this.holding.remove(job.id());
                this.gone.remove(job.id());
                Notices.show(new Notice(Notice.Kind.BAD, job.name(), Lang.get("hotwire.notice.download_failed"),
                        key));
            }
        }
    }

    private void showGetting(Downloads.Job job, String key) {
        boolean paused = job.state() == Downloads.State.PAUSED;
        int percent = (int) (job.getFraction() * 100) + (paused ? PAUSE_MARK : 0);
        Integer last = this.holding.get(job.id());
        if ((last != null && last == percent) || this.gone.contains(job.id())) {
            return;
        }
        Notice card = Notice.ofDownload(job.name(), Lang.get(paused ? "hotwire.notice.download_paused"
                : "hotwire.notice.downloading", describeSize(job)), key, (float) job.getFraction(), job.id(), paused);
        if (last != null) {
            if (Notices.replace(card)) {
                this.holding.put(job.id(), percent);
            } else {
                this.holding.remove(job.id());
                this.gone.add(job.id());
            }
        } else if (this.holding.size() < ROOM) {
            this.holding.put(job.id(), percent);
            Notices.show(card);
        }
    }

    public static String describeSize(Downloads.Job job) {
        double done = job.done() / 1_048_576.0;
        return job.total() > 0
                ? String.format(Locale.ROOT, "%.1f of %.1f MB", done, job.total() / 1_048_576.0)
                : String.format(Locale.ROOT, "%.1f MB", done);
    }
}
