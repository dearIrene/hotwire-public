package dev.moon.hotwire.ui;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

final class AheadRequests {
    private static final long DWELL_NANOS = 250_000_000L;

    private final ModScreen.Game game;

    private @Nullable String pointed;
    private long pointedSince;
    private boolean asked;
    private boolean askedOff;

    AheadRequests(ModScreen.Game game) {
        this.game = game;
    }

    void askForPointer(@Nullable String pointedNow, long nowNanos, boolean idle) {
        if (!Objects.equals(pointedNow, this.pointed)) {
            this.pointed = pointedNow;
            this.pointedSince = nowNanos;
            this.asked = false;
        } else if (pointedNow != null && nowNanos - this.pointedSince > DWELL_NANOS && idle
                && !(this.asked && this.askedOff == this.game.getCatalog().isOn(pointedNow))) {
            this.asked = true;
            this.askedOff = this.game.getCatalog().isOn(pointedNow);
            this.game.prepareAhead(List.of(pointedNow), this.askedOff);
        }
    }

    void askForPicks(List<String> on, List<String> off, boolean idle) {
        if (on.isEmpty() != off.isEmpty() && idle) {
            this.game.prepareAhead(on.isEmpty() ? off : on, !on.isEmpty());
        }
    }
}
