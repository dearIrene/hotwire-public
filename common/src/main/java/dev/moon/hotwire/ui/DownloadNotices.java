package dev.moon.hotwire.ui;

import dev.moon.hotwire.modrinth.Downloads;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DownloadNotices {
    private static final Map<Integer, Integer> PERCENT = new ConcurrentHashMap<>();

    private DownloadNotices() {
    }

    public static void show(Downloads.Job job) {
        String key = "download " + job.id();
        switch (job.state()) {
            case WAITING -> Notices.show(Notice.ofProgress(job.name(), Lang.get("hotwire.notice.download_waiting"),
                    key, 0));
            case GETTING -> {
                int percent = (int) (job.getFraction() * 100);
                Integer last = PERCENT.put(job.id(), percent);
                if (last == null || last != percent) {
                    Notices.show(Notice.ofProgress(job.name(), Lang.get("hotwire.notice.downloading",
                            describeSize(job)), key, (float) job.getFraction()));
                }
            }
            case DONE -> {
                PERCENT.remove(job.id());
                Notices.show(new Notice(Notice.Kind.DONE, job.name(), Lang.get("hotwire.notice.downloaded"), key));
            }
            case FAILED -> {
                PERCENT.remove(job.id());
                Notices.show(new Notice(Notice.Kind.BAD, job.name(), Lang.get("hotwire.notice.download_failed"),
                        key));
            }
        }
    }

    private static String describeSize(Downloads.Job job) {
        double done = job.done() / 1_048_576.0;
        return job.total() > 0
                ? String.format(Locale.ROOT, "%.1f of %.1f MB", done, job.total() / 1_048_576.0)
                : String.format(Locale.ROOT, "%.1f MB", done);
    }
}
