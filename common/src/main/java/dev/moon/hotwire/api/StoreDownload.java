package dev.moon.hotwire.api;

public record StoreDownload(String name, long done, long total, boolean over, boolean ok, int id, State state,
        String tag) {
    public StoreDownload(String name, long done, long total, boolean over, boolean ok, int id, State state) {
        this(name, done, total, over, ok, id, state, "");
    }

    public enum State {
        WAITING,
        GETTING,
        PAUSED,
        DONE,
        FAILED,
        STOPPED
    }

    public double fraction() {
        return this.total <= 0 ? 0 : Math.min(1, this.done / (double) this.total);
    }
}
