package dev.moon.hotwire.ui.list;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class ListAnimation {
    static final float FADE_OUT = 0.12f;
    static final float FADE_IN = 0.18f;
    static final float MOVE = 0.26f;
    static final float SURFACE = 0.42f;
    static final float JUMP = 3f;
    static final float STAGGER = 0.015f;
    static final float STAGGER_CAP = 0.3f;
    static final float OPEN_BEAT = 0.04f;
    static final float OPEN_CAP = 0.6f;

    static final float OPEN_RISE = -0.7f;
    static final float OPEN_SPAN = 0.34f;

    static final int OPEN_ROWS = 30;

    static final class Row {
        private final String id;
        private float y;
        private float side;
        private float alpha;
        private boolean ghost;
        private int slot;
        private Phase phase = Phase.AT_REST;
        private float yFrom;
        private float sideFrom;
        private float alphaFrom;
        private float at;
        private float span;
        private float inAt;
        private float rise;

        Row(String id, int slot) {
            this.id = id;
            this.slot = slot;
            this.y = slot;
        }

        String getId() {
            return this.id;
        }

        float getY() {
            return this.y;
        }

        float getSide() {
            return this.side;
        }

        float getAlpha() {
            return this.alpha;
        }

        boolean isGhost() {
            return this.ghost;
        }

        int getTarget() {
            return this.slot;
        }
    }

    private enum Phase { AT_REST, MOVING, LEAVE, CROSS, ARRIVE }

    private final Map<String, Row> rows = new HashMap<>();
    private final List<Row> drawable = new ArrayList<>();
    private float clock;
    private float replacedUntil = -1f;
    private int occupied;
    private float window = 12f;
    private float scrollTarget;
    private float scrollShown;
    private float scrollFrom;
    private float scrollAt = -1f;
    private float prevCeiling;

    void setWindow(float rows) {
        this.window = rows;
    }

    float getWindowSize() {
        return this.window;
    }

    float getScroll() {
        return this.scrollShown;
    }

    float getScrollTarget() {
        return this.scrollTarget;
    }

    int getOccupied() {
        return this.occupied;
    }

    @Nullable Row findRow(String id) {
        return this.rows.get(id);
    }

    List<Row> getRows() {
        return this.drawable;
    }

    boolean isReplacing() {
        return this.clock < this.replacedUntil;
    }

    float getTimeToArrive(String id) {
        Row row = this.rows.get(id);
        if (row == null || row.phase == Phase.AT_REST || row.phase == Phase.LEAVE) {
            return 0f;
        }
        float end = row.phase == Phase.MOVING ? row.at + row.span : row.inAt;
        return Math.max(0f, end - this.clock);
    }

    @Nullable String findSharedPlace() {
        List<Row> seen = new ArrayList<>();
        for (Row one : this.drawable) {
            float where = one.y - this.scrollShown;
            if (one.alpha > 0.2f && !one.ghost && one.side < 0.2f && where > -1f && where < this.window + 1f) {
                seen.add(one);
            }
        }
        for (int a = 0; a < seen.size(); a++) {
            for (int b = a + 1; b < seen.size(); b++) {
                if (Math.abs(seen.get(a).y - seen.get(b).y) < 0.5f) {
                    return "'" + seen.get(a).id + "' and '" + seen.get(b).id + "' are drawn on top of each other at"
                            + " row " + seen.get(a).y + " and " + seen.get(b).y;
                }
            }
        }
        return null;
    }

    void scrollTo(float target) {
        float clamped = Math.max(0f, Math.min(target, Math.max(0f, this.occupied - this.window)));
        if (clamped != this.scrollTarget) {
            this.scrollFrom = this.scrollShown;
            this.scrollAt = this.clock;
            this.scrollTarget = clamped;
        }
    }

    public void apply(List<String> order, boolean replaced, @Nullable String anchor) {
        boolean first = this.rows.isEmpty() && this.occupied == 0;
        boolean pinned = !replaced && this.prevCeiling > 0f && this.scrollTarget >= this.prevCeiling - 0.51f;
        this.occupied = order.size();
        this.rescroll(anchor, pinned, replaced || first);
        for (Row row : List.copyOf(this.rows.values())) {
            if (!order.contains(row.id) && row.phase != Phase.LEAVE) {
                this.leave(row, replaced);
            }
        }
        this.drawable.clear();
        int stagger = 0;
        for (int slot = 0; slot < order.size(); slot++) {
            Row row = this.rows.get(order.get(slot));
            if (row == null) {
                row = new Row(order.get(slot), slot);
                this.rows.put(row.id, row);
                this.enter(row, first, replaced);
            } else {
                row.slot = slot;
                if (row.phase == Phase.LEAVE) {
                    row.phase = Phase.AT_REST;
                }
                stagger = this.move(row, replaced, stagger);
            }
        }
        this.drawable.addAll(this.rows.values());
        this.drawable.sort((a, b) -> Boolean.compare(!a.ghost, !b.ghost));
        if (replaced && !first) {
            this.replacedUntil = this.clock + SURFACE;
        }
    }

    private void enter(Row row, boolean first, boolean replaced) {
        float opening = Math.min(this.occupied, Math.max(this.window, OPEN_ROWS) + 2f);
        if (first ? row.slot >= opening : this.isOffscreen(row.slot)) {
            this.stop(row);
            return;
        }
        row.phase = Phase.ARRIVE;
        row.side = replaced || first ? 0f : 1f;
        row.alpha = 0f;
        row.span = replaced ? SURFACE : FADE_IN;
        float delay = replaced ? 0f : FADE_OUT;
        if (first) {
            row.rise = OPEN_RISE;
            row.span = OPEN_SPAN;
            delay = 0.1f + Math.min(OPEN_CAP, row.slot * OPEN_BEAT);
        }
        row.inAt = this.clock + delay;
        row.ghost = !replaced;
    }

    private void leave(Row row, boolean replaced) {
        if (this.isOffscreen(row.y) || row.alpha < 0.05f) {
            this.rows.remove(row.id);
            return;
        }
        row.phase = Phase.LEAVE;
        row.yFrom = row.y;
        row.sideFrom = row.side;
        row.alphaFrom = row.alpha;
        row.at = this.clock;
        row.span = replaced ? SURFACE : FADE_OUT;
        row.ghost = true;
    }

    private int move(Row row, boolean replaced, int stagger) {
        float distance = row.slot - row.y;
        boolean gliding = row.phase == Phase.MOVING && row.yFrom + (row.slot - row.yFrom) != row.slot;
        if (Math.abs(distance) < 0.01f && row.phase == Phase.AT_REST) {
            return stagger;
        }
        if (this.isOffscreen(row.slot) && this.isOffscreen(row.y)) {
            this.stop(row);
            return stagger;
        }
        if (row.phase == Phase.ARRIVE || row.phase == Phase.CROSS) {
            row.inAt = Math.max(row.inAt, this.clock);
            return stagger;
        }
        if (!replaced && Math.abs(distance) > JUMP && row.alpha > 0.5f) {
            row.phase = Phase.CROSS;
            row.yFrom = row.y;
            row.sideFrom = row.side;
            row.alphaFrom = row.alpha;
            row.at = this.clock;
            row.inAt = this.clock + FADE_OUT;
            row.ghost = true;
            return stagger;
        }
        row.phase = Phase.MOVING;
        row.yFrom = row.y;
        row.sideFrom = row.side;
        row.alphaFrom = row.alpha;
        row.at = this.clock + (replaced || gliding ? 0f : Math.min(STAGGER_CAP, stagger * STAGGER));
        row.span = replaced ? SURFACE : MOVE;
        row.ghost = false;
        return stagger + 1;
    }

    private void stop(Row row) {
        row.phase = Phase.AT_REST;
        row.y = row.slot;
        row.rise = 0f;
        row.side = 0f;
        row.alpha = 1f;
        row.ghost = false;
    }

    private boolean isOffscreen(float at) {
        return at - this.scrollTarget < -2f || at - this.scrollTarget > this.window + 2f;
    }

    private void rescroll(@Nullable String anchor, boolean pinned, boolean atOnce) {
        float ceiling = Math.max(0f, this.occupied - this.window);
        Row anchored = anchor == null ? null : this.rows.get(anchor);
        if (anchored != null) {
            float screenAt = anchored.slot - this.scrollTarget;
            float chase = this.scrollTarget;
            if (screenAt < 0.5f) {
                chase = anchored.slot - 0.5f;
            } else if (screenAt > this.window - 1.5f) {
                chase = anchored.slot - this.window + 1.5f;
            }
            chase = Math.max(0f, Math.min(chase, ceiling));
            if (Math.abs(chase - this.scrollTarget) <= JUMP) {
                this.scrollTo(chase);
            }
        } else if (pinned && ceiling >= this.prevCeiling) {
            this.scrollTo(ceiling);
        }
        this.prevCeiling = ceiling;
        this.scrollTo(Math.min(this.scrollTarget, ceiling));
        if (atOnce) {
            this.scrollShown = this.scrollTarget;
            this.scrollAt = -1f;
        }
    }

    void step(float dt) {
        this.clock += dt;
        if (this.scrollAt >= 0f) {
            float t = (this.clock - this.scrollAt) / MOVE;
            this.scrollShown = this.scrollFrom + (this.scrollTarget - this.scrollFrom) * ease(t);
            if (t >= 1f) {
                this.scrollAt = -1f;
            }
        }
        boolean prune = false;
        for (Row row : this.drawable) {
            switch (row.phase) {
                case AT_REST -> { }
                case MOVING -> this.move(row);
                case LEAVE -> prune |= this.fadeOut(row) >= 1f;
                case CROSS -> {
                    if (this.fadeOut(row) >= 1f) {
                        row.phase = Phase.ARRIVE;
                        row.span = FADE_IN;
                        row.side = 1f;
                    }
                }
                case ARRIVE -> this.arrive(row);
            }
        }
        if (prune) {
            this.rows.values().removeIf(row -> row.phase == Phase.LEAVE && row.alpha <= 0.01f);
            this.drawable.removeIf(row -> row.phase == Phase.LEAVE && row.alpha <= 0.01f);
        }
    }

    private void move(Row row) {
        float t = ease((this.clock - row.at) / row.span);
        row.y = row.yFrom + (row.slot - row.yFrom) * t;
        row.side = row.sideFrom * (1f - t);
        row.alpha = row.alphaFrom + (1f - row.alphaFrom) * t;
        if (t >= 1f) {
            this.stop(row);
        }
    }

    private float fadeOut(Row row) {
        float t = ease((this.clock - row.at) / row.span);
        boolean inPlace = row.span == SURFACE;
        row.y = row.yFrom;
        row.side = inPlace ? 0f : row.sideFrom + (1f - row.sideFrom) * t;
        row.alpha = row.alphaFrom * (1f - t);
        row.ghost = true;
        return t;
    }

    private void arrive(Row row) {
        row.y = row.slot + row.rise;
        if (this.clock < row.inAt) {
            row.alpha = 0f;
            return;
        }
        float t = ease((this.clock - row.inAt) / row.span);
        row.y = row.slot + row.rise * (1f - t);
        row.side = row.side > 0f || row.span == FADE_IN ? 1f - t : 0f;
        row.alpha = t;
        row.ghost = t < 0.75f;
        if (t >= 1f) {
            this.stop(row);
        }
    }

    static float ease(float t) {
        if (t <= 0f) {
            return 0f;
        }
        if (t >= 1f) {
            return 1f;
        }
        float k = 1f - t;
        return 1f - k * k * k;
    }
}
