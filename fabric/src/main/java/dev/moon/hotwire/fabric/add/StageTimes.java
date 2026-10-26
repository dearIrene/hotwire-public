package dev.moon.hotwire.fabric.add;

final class StageTimes {
    private final StringBuilder told = new StringBuilder();
    private final long began = System.nanoTime();
    private long at = this.began;

    void end(String stage) {
        long now = System.nanoTime();
        this.told.append(this.told.isEmpty() ? "" : ", ").append(stage).append(' ').append((now - this.at) / 1_000_000)
                .append(" ms");
        this.at = now;
    }

    @Override
    public String toString() {
        return (System.nanoTime() - this.began) / 1_000_000 + " ms (" + this.told + ")";
    }
}
