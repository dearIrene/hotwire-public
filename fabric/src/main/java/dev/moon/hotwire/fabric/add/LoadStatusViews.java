package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.fabric.version.Bands;

public final class LoadStatusViews {
    private LoadStatusViews() {
    }

    public static void release() {
        Bands.releaseLoadStatusView();
    }
}
