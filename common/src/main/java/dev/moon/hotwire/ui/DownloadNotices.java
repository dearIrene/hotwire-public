package dev.moon.hotwire.ui;

import dev.moon.hotwire.modrinth.Downloads;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DownloadNotices {
    private static final int ROOM = 3;

    private static final int PAUSE_MARK = 1000;

    private static final Map<Integer, Integer> HOLDING = new HashMap<>();
    private static final Set<Integer> GONE = new HashSet<>();
    private static volatile boolean quiet;

    private DownloadNotices() {
    }

    public static void setQuiet(boolean shownElsewhere) {
        quiet = shownElsewhere;
    }

    public static synchronized void show(Downloads.Job job) {
        String key = "download " + job.id();
        switch (job.state()) {
            case WAITING -> {
            }
            case GETTING, PAUSED -> {
                if (!quiet) {
                    showGetting(job, key);
                }
            }
            case STOPPED -> {
                boolean inHand = HOLDING.remove(job.id()) != null;
                GONE.remove(job.id());
                if (inHand) {
                    Notices.show(new Notice(Notice.Kind.ASK, job.name(), Lang.get("hotwire.notice.download_stopped"),
                            key));
                }
            }
            case DONE -> {
                boolean inHand = HOLDING.remove(job.id()) != null;
                GONE.remove(job.id());
                if (inHand && !quiet) {
                    Notices.show(new Notice(Notice.Kind.DONE, job.name(), Lang.get("hotwire.notice.downloaded"),
                            key));
                }
            }
            case FAILED -> {
                HOLDING.remove(job.id());
                GONE.remove(job.id());
                Notices.show(new Notice(Notice.Kind.BAD, job.name(), Lang.get("hotwire.notice.download_failed"),
                        key));
            }
        }
    }

    private static void showGetting(Downloads.Job job, String key) {
        boolean paused = job.state() == Downloads.State.PAUSED;
        int percent = (int) (job.getFraction() * 100) + (paused ? PAUSE_MARK : 0);
        Integer last = HOLDING.get(job.id());
        if ((last != null && last == percent) || GONE.contains(job.id())) {
            return;
        }
        Notice card = Notice.ofDownload(job.name(), Lang.get(paused ? "hotwire.notice.download_paused"
                : "hotwire.notice.downloading", describeSize(job)), key, (float) job.getFraction(), job.id(), paused);
        if (last != null) {
            if (Notices.replace(card)) {
                HOLDING.put(job.id(), percent);
            } else {
                HOLDING.remove(job.id());
                GONE.add(job.id());
            }
        } else if (HOLDING.size() < ROOM) {
            HOLDING.put(job.id(), percent);
            Notices.show(card);
        }
    }

    private static String describeSize(Downloads.Job job) {
        double done = job.done() / 1_048_576.0;
        return job.total() > 0
                ? String.format(Locale.ROOT, "%.1f of %.1f MB", done, job.total() / 1_048_576.0)
                : String.format(Locale.ROOT, "%.1f MB", done);
    }
}
