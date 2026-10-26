package dev.moon.hotwire.ui.list;

import dev.moon.hotwire.ui.Canvas;
import dev.moon.hotwire.ui.Colors;
import dev.moon.hotwire.ui.EasedValue;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.Palette;
import org.jspecify.annotations.Nullable;

final class ModListRow {
    static final int ICON = 32;
    static final int SWITCH_W = 24;
    static final int SWITCH_H = 12;

    static final int SWITCH_ZONE = 92;

    static final int STAR_ZONE = 24;
    private static final float STAR_SCALE = 2f;
    private static final String STARRED = "\u2605";
    private static final String UNSTARRED = "\u2606";
    static final int CARD_SWITCH_ZONE_H = 24;

    private static final int[] TILES = {0xFF2B2447, 0xFF2E3A4F, 0xFF4A2F3F, 0xFF3A2F4F, 0xFF2B4549, 0xFF45352B,
        0xFF3F3346, 0xFF2F4A3F};
    private static final int ICON_OFF = 0xA00B0912;
    private static final int ICON_DISSOLVE = 0xFF140F1E;
    private static final int LOCK_SHEET = 0xC8060810;
    private static final int PICKED_TINT = 0x16FF7CC3;
    private static final int HOVER_TINT = 0x0EFFFFFF;
    private static final int OFF_FADE = 0x40FF5A6E;
    private static final int FLASH_ON = 0x48B48CFF;
    private static final int FLASH_OFF = 0x48FF5A6E;
    private static final int BOX_INNER = 0xFF0E0B17;
    private static final int SWITCH_LOCKED = 0xFF2A2140;
    private static final float TICK_FROM = 0.35f;

    enum Chip { LIBRARY, CLIENT, SERVER, BOTH }

    private final ModList list;
    private final String id;
    private ModStatus mod;
    private String subtitle = "";
    private Chip chip = Chip.BOTH;
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

    float getSide() {
        return this.side;
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

    @Nullable Boolean getWish() {
        return this.wish;
    }

    float getIndent() {
        if (!this.childSeen) {
            return this.pickWait <= 0 && this.child ? 1 : 0;
        }
        return this.childness;
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
            this.chip = Chip.LIBRARY;
            return;
        }
        this.chip = switch (badges.getEnvironmentOf(now.id())) {
            case "client" -> Chip.CLIENT;
            case "server" -> Chip.SERVER;
            default -> Chip.BOTH;
        };
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

    void paint(Canvas canvas, int x, int y, int w, int h, boolean card, float alpha) {
        ModCatalog catalog = this.list.getCatalog();
        boolean worldLocked = catalog.isLocked(this.id);
        boolean locked = catalog.isProtected(this.id) || worldLocked;
        boolean auto = this.list.getModel().getPickedForYou().contains(this.id);
        float appear = this.alpha * alpha;
        float onness = Math.max(0, Math.min(1, this.onness));
        this.paintGround(canvas, x, y, w, h, card, onness, appear);
        int accent = auto ? Palette.ACCENT_SOFT : Palette.ACCENT;
        String owner = this.list.getModel().findBroughtInBy(this.id);
        boolean needed = auto && owner != null && this.list.getModel().getChosen().contains(owner);
        if (card) {
            drawCheckbox(canvas, x + w - 22, y + 6, this.pick, needed, locked, accent, appear);
            this.paintIcon(canvas, appear, this.mod.on(), x + 8, y + 8);
            this.paintCardText(canvas, x, y, w, appear);
            this.paintStar(canvas, catalog, x + w - 22 - STAR_ZONE, y + 10, appear);
        } else {
            drawCheckbox(canvas, x + 11, y + h / 2 - 8, this.pick, needed, locked, accent, appear);
            this.paintIcon(canvas, appear, this.mod.on(), x + 38, y + h / 2 - ICON / 2);
            this.paintRowText(canvas, x, y, w, appear, auto);
            this.paintStar(canvas, catalog, x + w - SWITCH_ZONE - STAR_ZONE, y + h / 2 - 4, appear);
        }
        int switchX = x + w - 8 - SWITCH_W;
        int switchY = card ? y + h - 8 - SWITCH_H : y + h / 2 - SWITCH_H / 2;
        String word = Lang.get(onness > 0.5f ? "hotwire.screen.on" : "hotwire.screen.off");
        if (locked) {
            word = Lang.get(worldLocked ? "hotwire.row.locked" : "hotwire.row.always_on");
        }
        boolean hot = this.list.isPointerIn(x + w - SWITCH_ZONE, y, x + w, y + h);
        drawSwitch(canvas, switchX, switchY, onness, hot, locked, appear);
        if (!card || !locked) {
            int ink = locked ? Palette.FAINT : Colors.mix(Palette.OFF_TINT, Palette.ON, onness);
            canvas.drawText(word, switchX - 8 - canvas.measureText(word), switchY + 2,
                    Colors.fade(ink, appear * (locked || hot ? 1 : 0.6f)));
        }
        if (worldLocked && !card) {
            canvas.fill(x, y, x + w, y + h, Colors.fade(LOCK_SHEET, appear));
            String text = Lang.get("hotwire.row.world_locked");
            canvas.drawText(text, x + (w - canvas.measureText(text)) / 2, y + h / 2 - 4,
                    Colors.fade(Palette.TEXT, appear));
        }
    }

    private void paintGround(Canvas canvas, int x, int y, int w, int h, boolean card, float onness, float appear) {
        if (card) {
            canvas.fill(x, y, x + w, y + h, Colors.fade(Colors.mix(Palette.HAIRLINE, Palette.ACCENT, this.pick),
                    appear));
            canvas.fill(x + 1, y + 1, x + w - 1, y + h - 1, Colors.fade(Palette.PANEL, appear));
        } else {
            canvas.fill(x, y + h, x + w, y + h + ModList.GAP, Colors.fade(Palette.HAIRLINE, appear));
        }
        if (this.pick > 0.01f) {
            canvas.fill(x, y, x + w, y + h, Colors.fade(PICKED_TINT, appear * this.pick));
            if (!card) {
                int high = Math.round(h * this.pick);
                canvas.fill(x, y + (h - high) / 2, x + 3, y + (h + high) / 2, Colors.fade(Palette.ACCENT, appear));
            }
        }
        if (this.hover > 0.01f) {
            canvas.fill(x, y, x + w, y + h, Colors.fade(HOVER_TINT, appear * this.hover));
        }
        float off = 1 - onness;
        if (off > 0.01f) {
            int from = card ? x : x + w * 2 / 5;
            canvas.fillAcross(from, y, x + w, y + h, Colors.fade(OFF_FADE, 0), Colors.fade(OFF_FADE, appear * off));
        }
        if (this.justChanged > 0.01f) {
            canvas.fill(x, y, x + w, y + h, Colors.fade(onness > 0.5f ? FLASH_ON : FLASH_OFF,
                    appear * this.justChanged * 0.8f));
        }
    }

    private void paintStar(Canvas canvas, ModCatalog catalog, int x, int y, float appear) {
        boolean starred = catalog.isFavorite(this.id);
        if (!starred && this.hover < 0.01f) {
            return;
        }
        String glyph = starred ? STARRED : UNSTARRED;
        float centerX = x + STAR_ZONE / 2f;
        float centerY = y + 4;
        canvas.pushScale(STAR_SCALE, centerX, centerY);
        canvas.drawText(glyph, Math.round(centerX - canvas.measureText(glyph) / 2f), y, Colors.fade(starred
                ? Palette.ACCENT : Palette.DIM, starred ? appear : appear * this.hover));
        canvas.popScale();
    }

    private void paintRowText(Canvas canvas, int x, int y, int w, float appear, boolean auto) {
        boolean on = this.mod.on();
        int textX = x + 38 + ICON + 12;
        int room = x + w - SWITCH_ZONE - STAR_ZONE - 8 - textX;
        this.measure(canvas, room);
        canvas.drawText(this.nameFit, textX, y + 8, Colors.fade(on ? Palette.TEXT : Palette.DIM, appear));
        canvas.drawText(this.subFit, textX, y + 25, Colors.fade(Palette.FAINT, appear));
        int after = textX + this.subWidth + (this.subWidth > 0 ? 12 : 0);
        after = this.paintMeta(canvas, after, y + 25, textX + room, appear);
        if (auto && this.pick > 0.5f) {
            String neededText = Lang.get("hotwire.row.needed");
            int neededW = canvas.measureText(neededText);
            if (after + neededW < textX + room) {
                canvas.drawText(neededText, textX + room - neededW, y + 25,
                        Colors.fade(Palette.ACCENT_SOFT, appear * Math.min(1, (this.pick - 0.5f) * 4)));
            }
        }
    }

    private void paintCardText(Canvas canvas, int x, int y, int w, float appear) {
        boolean on = this.mod.on();
        int textX = x + 8 + ICON + 8;
        int room = x + w - 24 - textX;
        this.measure(canvas, room);
        canvas.drawText(this.nameFit, textX, y + 10, Colors.fade(on ? Palette.TEXT : Palette.DIM, appear));
        canvas.drawText(canvas.clip(this.mod.version(), room), textX, y + 23, Colors.fade(Palette.FAINT, appear));
        this.paintMeta(canvas, textX, y + 36, x + w - SWITCH_ZONE, appear);
    }

    private int paintMeta(Canvas canvas, int x, int y, int right, float appear) {
        int at = x;
        String chip = Lang.get(switch (this.chip) {
            case LIBRARY -> "hotwire.row.library";
            case CLIENT -> "hotwire.row.client";
            case SERVER -> "hotwire.row.server";
            case BOTH -> "hotwire.row.both";
        });
        int chipW = canvas.measureText(chip);
        if (at + chipW <= right) {
            canvas.drawText(chip, at, y, Colors.fade(this.chip == Chip.LIBRARY ? Palette.ACCENT_SOFT : Palette.DIM,
                    appear * 0.9f));
            at += chipW + 12;
        }
        if (this.mod.keptOff()) {
            String kept = Lang.get("hotwire.row.kept_off");
            int keptW = canvas.measureText(kept);
            if (at + keptW <= right) {
                canvas.drawText(kept, at, y, Colors.fade(Palette.OFF_TINT, appear * 0.9f));
                at += keptW + 12;
            }
        }
        int count = this.dependantTick.getRounded();
        if (count > 0) {
            String text = count == 1 ? Lang.get("hotwire.row.needed_by_one") : Lang.get("hotwire.row.needed_by",
                    count);
            int textW = canvas.measureText(text);
            if (at + textW <= right) {
                canvas.drawText(text, at, y, Colors.fade(Palette.DIM, appear * 0.9f));
                at += textW + 12;
            }
        }
        return at;
    }

    private void measure(Canvas canvas, int room) {
        if (this.measuredRoom != room) {
            this.measuredRoom = room;
            this.nameFit = canvas.clip(this.mod.name(), room);
            this.subFit = canvas.clip(this.subtitle, room);
            this.subWidth = canvas.measureText(this.subFit);
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
            canvas.fill(x, y, x + 16, y + 16, Colors.fade(Palette.HAIRLINE, alpha));
            canvas.fill(x + 1, y + 1, x + 15, y + 15, Colors.fade(BOX_INNER, alpha * 0.6f));
            return;
        }
        canvas.fill(x, y, x + 16, y + 16, Colors.fade(Colors.mix(Palette.PANEL_EDGE, accent, pick), alpha));
        canvas.fill(x + 1, y + 1, x + 15, y + 15, Colors.fade(Colors.mix(BOX_INNER, accent, pick), alpha));
        if (pick <= TICK_FROM) {
            return;
        }
        int ink = Colors.fade(Palette.ON_ACCENT, alpha * (pick - TICK_FROM) / (1 - TICK_FROM));
        if (needed) {
            canvas.fill(x + 5, y + 5, x + 11, y + 11, ink);
            return;
        }
        canvas.fill(x + 4, y + 8, x + 7, y + 11, ink);
        canvas.fill(x + 7, y + 5, x + 12, y + 9, ink);
    }

    static void drawSwitch(Canvas canvas, int x, int y, float onness, boolean hot, boolean locked, float alpha) {
        if (locked) {
            canvas.fill(x, y, x + SWITCH_W, y + SWITCH_H, Colors.fade(SWITCH_LOCKED, alpha));
            canvas.fill(x + 1, y + 1, x + SWITCH_W - 1, y + SWITCH_H - 1, Colors.fade(Palette.PANEL, alpha));
            int cx = x + SWITCH_W / 2;
            int ink = Colors.fade(Palette.FAINT, alpha);
            canvas.fill(cx - 3, y + 5, cx + 3, y + 10, ink);
            canvas.fill(cx - 2, y + 3, cx - 1, y + 5, ink);
            canvas.fill(cx + 1, y + 3, cx + 2, y + 5, ink);
            canvas.fill(cx - 2, y + 2, cx + 2, y + 3, ink);
            return;
        }
        int colour = Colors.mix(Palette.OFF_TINT, Palette.ON, onness);
        canvas.fill(x, y, x + SWITCH_W, y + SWITCH_H, Colors.fade(colour, alpha * (hot ? 1 : 0.8f)));
        canvas.fill(x + 1, y + 1, x + SWITCH_W - 1, y + SWITCH_H - 1, Colors.fade(Palette.PANEL, alpha));
        int knobW = 9;
        int knob = Math.round(x + 2 + onness * (SWITCH_W - 4 - knobW));
        canvas.fill(knob, y + 2, knob + knobW, y + SWITCH_H - 2, Colors.fade(colour, alpha));
    }
}
