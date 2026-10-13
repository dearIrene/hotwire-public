package dev.moon.hotwire.ui.list;

import dev.moon.hotwire.ui.Canvas;
import dev.moon.hotwire.ui.Colors;
import dev.moon.hotwire.ui.EasedValue;
import dev.moon.hotwire.ui.Palette;
import org.jspecify.annotations.Nullable;

final class ModListRow {
    static final int CHIP_LIBRARY = 0xFFB98CE8;
    static final int CHIP_CLIENT = 0xFF7FA6E8;
    static final int CHIP_SERVER = 0xFFE0A45C;
    private static final String WORLD_LOCKED = "Can't be done unless you're not in a world.";
    private static final String NEEDED = "needed by your selection";

    private static final int ICON = 32;
    private static final int CHIP_GAP = 16;
    private static final int ROW_BASE = 0x0AFFFFFF;

    private static final int[] TILES = {0xFF2B3A55, 0xFF2E4A3F, 0xFF4A3A2B, 0xFF4A2F3F, 0xFF3A2F4F, 0xFF2B4549,
        0xFF45452B, 0xFF3F3346};
    private static final int ICON_OFF = 0xA00B0E14;
    private static final int ICON_DISSOLVE = 0xFF141720;
    private static final int LOCK_SHEET = 0xC8060810;
    private static final int BOX_LOCKED = 0x11FFFFFF;
    private static final int RING_REST = 0x22FFFFFF;
    private static final int RING_NEEDED = 0x557C8BA8;
    private static final int INNER_REST = 0xFF1A1E28;
    private static final int TICK = 0xFF0B0E14;
    private static final int PILL_LOCKED = 0x12FFFFFF;
    private static final int TRACK_HOT = 0x2EFFFFFF;
    private static final int TRACK_REST = 0x18FFFFFF;
    private static final float TICK_FROM = 0.35f;

    private final ModList list;
    private final String id;
    private ModStatus mod;
    private String subtitle = "";
    private String chip = "";
    private int chipColour;
    private boolean chipGradient;
    private boolean child;
    private int dependants;
    private final EasedValue dependantTick = EasedValue.ofSpeed(9);

    private float y;
    private float side;
    private float alpha = 1;
    private boolean ghost;
    private float hover;
    private float pick;
    private float pickWait;
    private boolean seen;
    private float onness;
    private float justChanged;
    private @Nullable Boolean wish;
    private boolean childSeen;
    private float childness;

    private int measuredRoom = -1;
    private String nameFit = "";
    private String subFit = "";
    private int subWidth;
    private int chipWidth;

    ModListRow(ModList list, ModStatus mod) {
        this.list = list;
        this.id = mod.id();
        this.mod = mod;
    }

    String getId() {
        return this.id;
    }

    ModStatus getMod() {
        return this.mod;
    }

    float getY() {
        return this.y;
    }

    float getAlpha() {
        return this.alpha;
    }

    boolean isGhost() {
        return this.ghost;
    }

    boolean isChild() {
        return this.child;
    }

    float getPick() {
        return this.pick;
    }

    float getOnness() {
        return this.onness;
    }

    float getJustChanged() {
        return this.justChanged;
    }

    float getChildness() {
        return this.childness;
    }

    @Nullable Boolean getWish() {
        return this.wish;
    }

    void restate(ModStatus now, ModList.Badges badges) {
        this.mod = now;
        this.measuredRoom = -1;
        String authors = now.authors().strip();
        if (authors.isEmpty()) {
            this.subtitle = now.version();
        } else if (now.version().isEmpty()) {
            this.subtitle = authors;
        } else {
            this.subtitle = now.version() + "  ·  " + authors;
        }
        if (now.library() || badges.isDeclaredLibrary(now.id())) {
            this.setChip("Library", CHIP_LIBRARY, false);
            return;
        }
        switch (badges.getEnvironmentOf(now.id())) {
            case "client" -> this.setChip("Client", CHIP_CLIENT, false);
            case "server" -> this.setChip("Server", CHIP_SERVER, false);
            default -> this.setChip("Client & Server", 0, true);
        }
    }

    private void setChip(String text, int colour, boolean gradient) {
        this.chip = text;
        this.chipColour = colour;
        this.chipGradient = gradient;
    }

    void regroup(boolean child, int dependants) {
        this.child = child;
        this.dependants = dependants;
    }

    void delayPick(float seconds) {
        this.pickWait = seconds;
    }

    void setWish(boolean on) {
        this.wish = on;
        this.justChanged = 1;
    }

    void clearWish() {
        this.wish = null;
    }

    void step(float dt, boolean picked) {
        boolean liveOn = this.mod.on();
        if (this.wish != null && this.wish == liveOn) {
            this.wish = null;
        }
        boolean shownOn = this.wish != null ? this.wish : liveOn;
        float wants = shownOn ? 1 : 0;
        if (!this.seen) {
            this.seen = true;
            this.onness = wants;
        } else if (this.onness > 0.5f != liveOn) {
            this.justChanged = 1;
        }
        this.onness = EasedValue.lerp(this.onness, wants, EasedValue.computeFactor(11, dt));
        this.justChanged = EasedValue.lerp(this.justChanged, 0, EasedValue.computeFactor(3.2f, dt));
        if (this.pickWait > 0) {
            this.pickWait -= dt;
        } else {
            this.pick = EasedValue.lerp(this.pick, picked ? 1 : 0, EasedValue.computeFactor(16, dt));
            float wantsChild = this.child ? 1 : 0;
            this.childness = this.childSeen
                    ? EasedValue.lerp(this.childness, wantsChild, EasedValue.computeFactor(15, dt)) : wantsChild;
            this.childSeen = true;
        }
        this.dependantTick.to(this.dependants);
        this.dependantTick.step(dt);
    }

    void place(ListAnimation.Row place, float scrollTarget) {
        this.y = place.getY() - scrollTarget;
        this.side = place.getSide();
        this.alpha = place.getAlpha();
        this.ghost = place.isGhost();
    }

    void easeHover(boolean over, float dt) {
        this.hover = EasedValue.lerp(this.hover, over ? 1 : 0, EasedValue.computeFactor(20, dt));
    }

    void paint(Canvas canvas, int y, float alpha) {
        ModCatalog catalog = this.list.getCatalog();
        ModListModel model = this.list.getModel();
        boolean worldLocked = catalog.isLocked(this.id);
        boolean locked = catalog.isProtected(this.id) || worldLocked;
        boolean auto = model.getPickedForYou().contains(this.id);
        int accent = auto ? Palette.ACCENT_SOFT : Palette.ACCENT;
        float appear = this.alpha * alpha;
        float indent = this.childness;
        if (!this.childSeen) {
            indent = this.pickWait <= 0 && this.child ? 1 : 0;
        }
        int travel = Math.round(this.side * ModList.SIDE);
        int left = 30 + Math.round(18 * indent) + travel;
        int right = this.list.getWidth() - 30 + travel;
        if (indent > 0.01f) {
            int spine = 38;
            int ink = Colors.fade(ModList.SPINE, appear * indent);
            canvas.fill(spine, y - ModList.GAP, spine + 1, y + ModList.ROW / 2, ink);
            canvas.fill(spine, y + ModList.ROW / 2, Math.max(spine + 1, left - 4), y + ModList.ROW / 2 + 1, ink);
        }
        int base = Colors.mix(ROW_BASE, auto ? 0x2A7C8BA8 : 0x3348A0FF, this.pick);
        base = Colors.mix(base, this.mod.on() ? 0x445AD98A : 0x4456A8FF, this.justChanged * 0.8f);
        canvas.fill(left, y, right, y + ModList.ROW, Colors.fade(Colors.mix(base, 0x1AFFFFFF, this.hover * 0.6f),
                appear));
        if (this.pick > 0.01f) {
            int high = (int) (ModList.ROW * this.pick);
            canvas.fill(left, y + (ModList.ROW - high) / 2, left + 3, y + (ModList.ROW + high) / 2,
                    Colors.fade(accent, appear));
        }
        String owner = model.findBroughtInBy(this.id);
        boolean needed = auto && owner != null && model.getChosen().contains(owner);
        drawCheckbox(canvas, left + 11, y + ModList.ROW / 2 - 8, this.pick, needed, locked, accent, appear);
        this.paintText(canvas, left, right, y, appear, auto);
        int pillX = this.list.getWidth() - 128;
        boolean overPill = this.list.isPointerIn(pillX, y + 11, pillX + 80, y + 31);
        String lockedWord = null;
        if (locked) {
            lockedWord = worldLocked ? "locked" : "always on";
        }
        drawPill(canvas, pillX, y + ModList.ROW / 2 - 10, Math.max(0, Math.min(1, this.onness)), overPill,
                lockedWord, appear);
        if (worldLocked) {
            canvas.fill(left, y, right, y + ModList.ROW, Colors.fade(LOCK_SHEET, appear));
            canvas.drawText(WORLD_LOCKED, (left + right - canvas.measureText(WORLD_LOCKED)) / 2,
                    y + ModList.ROW / 2 - 4, Colors.fade(Palette.TEXT, appear));
        }
    }

    private void paintText(Canvas canvas, int left, int right, int y, float appear, boolean auto) {
        boolean on = this.mod.on();
        int iconX = left + 38;
        this.paintIcon(canvas, appear, on, iconX, y + ModList.ROW / 2 - ICON / 2);
        int textX = iconX + ICON + 12;
        int room = right - textX - 110;
        if (this.measuredRoom != room) {
            this.measuredRoom = room;
            this.nameFit = canvas.clip(this.mod.name(), room);
            this.subFit = canvas.clip(this.subtitle, room);
            this.subWidth = canvas.measureText(this.subFit);
            this.chipWidth = canvas.measureText(this.chip);
        }
        canvas.drawText(this.nameFit, textX, y + 8, Colors.fade(on ? Palette.TEXT : Palette.DIM, appear));
        canvas.drawText(this.subFit, textX, y + 25, Colors.fade(Palette.FAINT, appear));
        int after = textX + this.subWidth + (this.subWidth > 0 ? CHIP_GAP : 0);
        if (!this.chip.isEmpty() && after + this.chipWidth + CHIP_GAP < textX + room) {
            drawChip(canvas, after + Math.round((1 - appear) * 8), y + 20, this.chip, this.chipColour,
                    this.chipGradient, appear);
            after += this.chipWidth + 10 + CHIP_GAP;
        }
        int count = this.dependantTick.getRounded();
        if (count > 0) {
            String text = "Dependants: " + count;
            if (after + canvas.measureText(text) < textX + room) {
                canvas.drawText(text, after, y + 25, Colors.fade(Palette.ACCENT_SOFT, appear * 0.85f));
            }
        }
        if (auto && this.pick > 0.5f) {
            canvas.drawText(NEEDED, right - 130 - canvas.measureText(NEEDED), y + 17,
                    Colors.fade(Palette.ACCENT_SOFT, appear * Math.min(1, (this.pick - 0.5f) * 4)));
        }
    }

    private void paintIcon(Canvas canvas, float appear, boolean on, int x, int y) {
        Canvas.Image image = this.list.findIcon(this.id);
        if (image == null || !canvas.drawImage(image, x, y, ICON, ICON, 1)) {
            canvas.fill(x, y, x + ICON, y + ICON, TILES[Math.floorMod(this.id.hashCode(), TILES.length)]);
            String initial = getInitial(this.mod.name());
            canvas.pushScale(2, x + ICON / 2f, y + ICON / 2f);

            canvas.drawText(initial, x + (ICON - canvas.measureText(initial) + 1) / 2, y + ICON / 2 - 3, Palette.TEXT);
            canvas.popScale();
        }
        if (!on) {
            canvas.fill(x, y, x + ICON, y + ICON, ICON_OFF);
        }
        if (appear < 1) {
            canvas.fill(x, y, x + ICON, y + ICON, Colors.fade(ICON_DISSOLVE, 1 - appear));
        }
    }

    private static String getInitial(String name) {
        int first = name.codePoints().filter(Character::isLetterOrDigit).findFirst().orElse('?');
        return Character.toString(Character.toUpperCase(first));
    }

    static void drawCheckbox(Canvas canvas, int x, int y, float pick, boolean needed, boolean locked, int accent,
            float alpha) {
        if (locked && !needed) {
            canvas.fill(x + 1, y + 1, x + 15, y + 15, Colors.fade(BOX_LOCKED, alpha));
            return;
        }
        canvas.fill(x, y, x + 16, y + 16,
                Colors.fade(Colors.mix(RING_REST, needed ? RING_NEEDED : accent, pick), alpha));
        canvas.fill(x + 1, y + 1, x + 15, y + 15,
                Colors.fade(Colors.mix(INNER_REST, needed ? Palette.ACCENT_SOFT : accent, pick), alpha));
        if (pick > TICK_FROM) {
            int tick = Colors.fade(TICK, alpha * (pick - TICK_FROM) / (1 - TICK_FROM));
            canvas.fill(x + 4, y + 8, x + 7, y + 11, tick);
            canvas.fill(x + 7, y + 5, x + 12, y + 9, tick);
        }
    }

    static void drawPill(Canvas canvas, int x0, int y0, float onness, boolean hot, @Nullable String lockedText,
            float alpha) {
        int x1 = x0 + 80;
        int y1 = y0 + 20;
        if (lockedText != null) {
            canvas.fill(x0, y0, x1, y1, Colors.fade(PILL_LOCKED, alpha));
            canvas.drawText(lockedText, x0 + 10, y0 + 6, Colors.fade(Palette.FAINT, alpha));
            return;
        }
        canvas.fill(x0, y0, x1, y1, Colors.fade(hot ? TRACK_HOT : TRACK_REST, alpha));
        canvas.drawText("ON", x0 + 8, y0 + 6, Colors.fade(Palette.TEXT, alpha));
        canvas.drawText("OFF", x1 - 22 + Math.max(0, (20 - canvas.measureText("OFF")) / 2), y0 + 6,
                Colors.fade(Palette.DIM, alpha));
        int knob = Math.round((x0 + 2) + onness * ((x1 - 22) - (x0 + 2)));
        canvas.fill(knob, y0 + 2, knob + 20, y1 - 2, Colors.fade(Colors.mix(Palette.OFF, Palette.ON, onness), alpha));
    }

    static void drawChip(Canvas canvas, int x, int y, String text, int colour, boolean bothSides, float alpha) {
        int w = canvas.measureText(text) + 10;
        int h = 13;
        if (bothSides) {
            drawGradient(canvas, x + 1, x + w - 1, y + 1, y + h - 1, alpha * 0.34f);
        } else {
            canvas.fill(x + 1, y + 1, x + w - 1, y + h - 1, Colors.fade(colour, alpha * 0.16f));
        }
        int left = Colors.fade(bothSides ? CHIP_CLIENT : colour, alpha * 0.22f);
        int right = Colors.fade(bothSides ? CHIP_SERVER : colour, alpha * 0.22f);
        int mid = x + w / 2;
        canvas.fill(x + 2, y, mid, y + 1, left);
        canvas.fill(mid, y, x + w - 2, y + 1, right);
        canvas.fill(x + 2, y + h - 1, mid, y + h, left);
        canvas.fill(mid, y + h - 1, x + w - 2, y + h, right);
        canvas.drawText(text, x + 5, y + 2, Colors.fade(bothSides ? Palette.TEXT : colour, alpha * 0.95f));
    }

    private static void drawGradient(Canvas canvas, int x0, int x1, int y0, int y1, float alpha) {
        int span = Math.max(1, x1 - x0);
        int runFrom = x0;
        int runColour = Colors.fade(Colors.blend(CHIP_CLIENT, CHIP_SERVER, 0), alpha);
        for (int cx = x0 + 1; cx <= x1; cx++) {
            int here = cx == x1 ? runColour + 1
                    : Colors.fade(Colors.blend(CHIP_CLIENT, CHIP_SERVER, (cx - x0) / (float) span), alpha);
            if (here != runColour) {
                canvas.fill(runFrom, y0, cx, y1, runColour);
                runFrom = cx;
                runColour = here;
            }
        }
    }
}
