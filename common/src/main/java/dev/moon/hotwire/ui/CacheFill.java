package dev.moon.hotwire.ui;

import java.util.Locale;

public record CacheFill(int done, int of, boolean running, long savedBytes, long estimatedBytes, long millisLeft) {
    public boolean isFull() {
        return this.of > 0 && this.done >= this.of;
    }

    static String describeBytes(long bytes) {
        if (bytes < 1024 * 1024) {
            return Math.max(1, bytes / 1024) + " KB";
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    static String describeMillis(long millis) {
        long minutes = Math.round(millis / 60_000.0);
        return minutes < 1 ? Lang.get("hotwire.cache.under_a_minute") : Lang.get("hotwire.cache.minutes", minutes);
    }
}
