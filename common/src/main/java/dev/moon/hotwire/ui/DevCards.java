package dev.moon.hotwire.ui;

import dev.moon.hotwire.dev.DevCrash;
import dev.moon.hotwire.dev.DevEvent;
import dev.moon.hotwire.dev.DevHost;
import dev.moon.hotwire.dev.DevState;
import java.util.List;

final class DevCards {
    private static final int CARD = 0xFF1A1427;

    private static final float MOST_CLEAR = 0.6f;

    private static float solid = 1;

    private DevCards() {
    }

    static Part makeCard(int stripe, String heading, String aside, int ink, List<String> lines) {
        Line inside = Kit.column(Kit.row(Kit.text(heading).wrap().grow(1), Kit.text(aside, Palette.FAINT)).gap(6)
                .cross(Line.Cross.START)).gap(3).pad(8, 6);
        lines.forEach(line -> inside.with(makeLine(line, ink)));
        return makePane(CARD, Kit.row(new Kit.Fill(stripe).width(2), inside.grow(1)).cross(Line.Cross.STRETCH));
    }

    static void setSeeThrough(double see) {
        solid = 1 - (float) Math.clamp(see, 0, 1) * MOST_CLEAR;
    }

    static float getSolid() {
        return solid;
    }

    static Part makePane(int face, Part inner) {
        return new Pane(face, inner);
    }

    private static final class Pane extends Part {
        private final int face;
        private final Part inner;

        Pane(int face, Part inner) {
            this.face = face;
            this.inner = this.add(inner);
        }

        @Override
        protected void measure(Canvas canvas, int room) {
            this.inner.measure(canvas, room);
            this.hugWidth = this.inner.findWidth();
            this.hugHeight = this.inner.findHeight();
        }

        @Override
        protected void place(Canvas canvas) {
            this.inner.lay(canvas, this.getX(), this.getY(), this.getWidth(), this.getHeight());
        }

        @Override
        protected void paint(Canvas canvas, float alpha) {
            canvas.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(),
                    Colors.fade(this.face, alpha * solid));
        }
    }

    static Part makeCrash(DevHost host, String id, DevCrash crash, String when) {
        boolean placed = !crash.source().isEmpty();
        Part made = makeCard(Palette.BAD, crash.what(), when, Palette.DIM, placed ? List.of(crash.where(),
                Lang.get("hotwire.dev.crash.open")) : List.of(crash.where()));
        return placed ? made.press((button, modifiers) -> host.openSource(id, crash)) : made;
    }

    static Text makeLine(String words, int ink) {
        return Kit.text(words, ink).wrap();
    }

    static Line makeList() {
        return Kit.column().gap(4).pad(8, 8);
    }

    static int findColor(DevState state) {
        return switch (state) {
            case UP_TO_DATE -> Palette.GOOD;
            case LOADING, WAITING -> Palette.WARN;
            case NEEDS_RESTART -> Palette.ON;
            case FAILED -> Palette.BAD;
        };
    }

    static int findColor(DevEvent.Kind kind) {
        return switch (kind) {
            case IN, RESTARTED, ASSETS -> Palette.GOOD;
            case MERGED -> Palette.ACCENT_SOFT;
            case NEEDS_RESTART -> Palette.ON;
            case FAILED, CRASH, LEAK -> Palette.BAD;
        };
    }

    static String describeAgo(long at) {
        long seconds = Math.max(0, (System.currentTimeMillis() - at) / 1000);
        if (seconds < 60) {
            return Lang.get("hotwire.dev.ago.seconds", seconds);
        }
        return seconds < 3600 ? Lang.get("hotwire.dev.ago.minutes", seconds / 60)
                : Lang.get("hotwire.dev.ago.hours", seconds / 3600);
    }
}
