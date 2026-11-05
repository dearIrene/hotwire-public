package dev.moon.hotwire.ui;

import dev.moon.hotwire.dev.DevCrash;
import dev.moon.hotwire.dev.DevEvent;
import dev.moon.hotwire.dev.DevHost;
import dev.moon.hotwire.dev.DevMixin;
import dev.moon.hotwire.dev.DevModView;
import dev.moon.hotwire.dev.DevState;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class DevWindow extends Element {
    private static final int TITLE_H = 20;
    private static final int TABS_H = 16;
    private static final int PAD = 8;
    private static final int GRIP = 10;
    private static final int LINE = 11;
    private static final int MIN_W = 240;
    private static final int MIN_H = 150;
    private static final int MARGIN = 4;
    private static final int BUTTON_H = 18;
    private static final float REFRESH_SECONDS = 0.25f;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    private enum Tab { OVERVIEW, HISTORY, CRASHES, MIXINS }

    private enum Hold { NONE, MOVE, SIZE }

    private final DevHost host;
    private final String runtime;
    private final boolean fullRuntime;
    private final EasedValue shown = EasedValue.ofSpeed(14);
    private final EasedValue tabLeft = EasedValue.ofSpeed(18);
    private final EasedValue tabWidth = EasedValue.ofSpeed(18);
    private final EasedValue scroll = EasedValue.ofSpeed(14);
    private final Button reload;
    private final Button restart;
    private final Button pause;
    private final Button resume;
    private float scrollTarget;
    private float sinceRead = REFRESH_SECONDS;
    private List<DevModView> mods = List.of();
    private int modIndex;
    private Tab tab = Tab.OVERVIEW;
    private Hold hold = Hold.NONE;
    private double holdX;
    private double holdY;
    private int startLeft;
    private int startTop;
    private int startWidth;
    private int startHeight;
    private int left = 12;
    private int top = 12;
    private int width = 300;
    private int height = 210;
    private int screenWidth = 854;
    private int screenHeight = 480;
    private int contentHeight;

    private final Map<Tab, Integer> tabWidths = new EnumMap<>(Tab.class);

    @SuppressWarnings("this-escape")
    public DevWindow(DevHost host, String runtime, boolean fullRuntime) {
        this.host = host;
        this.runtime = runtime;
        this.fullRuntime = fullRuntime;
        this.reload = this.add(new Button(Lang.get("hotwire.dev.button.reload"), true, () -> this.act(true)));
        this.restart = this.add(new Button(Lang.get("hotwire.dev.button.restart"), false, () -> this.act(false)));
        this.pause = this.add(new Button(Lang.get("hotwire.dev.button.pause"), false, () -> host.setWatching(false)));
        this.resume = this.add(new Button(Lang.get("hotwire.dev.button.resume"), false, () -> host.setWatching(true)));
        this.shown.snap(0);
    }

    private void act(boolean reloadNow) {
        DevModView mod = this.findMod();
        if (mod != null) {
            if (reloadNow) {
                this.host.reloadNow(mod.id());
            } else {
                this.host.restartMod(mod.id());
            }
        }
    }

    private @Nullable DevModView findMod() {
        return this.mods.isEmpty() ? null : this.mods.get(Math.min(this.modIndex, this.mods.size() - 1));
    }

    public void setScreen(int width, int height) {
        this.screenWidth = width;
        this.screenHeight = height;
        this.clampToScreen();
    }

    private void clampToScreen() {
        int maxWidth = Math.max(MIN_W, this.screenWidth - 2 * MARGIN);
        int maxHeight = Math.max(MIN_H, this.screenHeight - 2 * MARGIN);
        this.width = Math.clamp(this.width, MIN_W, maxWidth);
        this.height = Math.clamp(this.height, MIN_H, maxHeight);
        this.left = Math.clamp(this.left, MARGIN, Math.max(MARGIN, this.screenWidth - this.width - MARGIN));
        this.top = Math.clamp(this.top, MARGIN, Math.max(MARGIN, this.screenHeight - this.height - MARGIN));
    }

    @Override
    public void step(float dt) {
        this.shown.to(1);
        this.shown.step(dt);
        this.sinceRead += dt;
        if (this.sinceRead >= REFRESH_SECONDS) {
            this.sinceRead = 0;
            this.mods = this.host.getDevMods();
        }
        int room = this.height - TITLE_H - TABS_H - PAD;
        this.scrollTarget = Math.clamp(this.scrollTarget, 0, Math.max(0, this.contentHeight - room));
        this.scroll.to(this.scrollTarget);
        this.scroll.step(dt);
        this.tabLeft.step(dt);
        this.tabWidth.step(dt);
        this.setBounds(this.left, this.top, this.width, this.height);
        boolean watching = this.host.isWatching();
        boolean overview = this.tab == Tab.OVERVIEW && this.findMod() != null;
        int buttonsY = this.top + this.height - BUTTON_H - PAD;
        int buttonW = (this.width - 4 * PAD) / 3;
        this.reload.setVisible(overview);
        this.restart.setVisible(overview);
        this.pause.setVisible(overview && watching);
        this.resume.setVisible(overview && !watching);
        this.reload.setBounds(this.left + PAD, buttonsY, buttonW, BUTTON_H);
        this.restart.setBounds(this.left + 2 * PAD + buttonW, buttonsY, buttonW, BUTTON_H);
        this.pause.setBounds(this.left + 3 * PAD + 2 * buttonW, buttonsY, buttonW, BUTTON_H);
        this.resume.setBounds(this.left + 3 * PAD + 2 * buttonW, buttonsY, buttonW, BUTTON_H);
        super.step(dt);
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        if (button != 0) {
            return true;
        }
        if (px >= this.left + this.width - GRIP && py >= this.top + this.height - GRIP) {
            this.grab(Hold.SIZE, px, py);
        } else if (py < this.top + TITLE_H) {
            if (this.mods.size() > 1 && px < this.left + this.width / 2.0) {
                this.modIndex = (this.modIndex + 1) % this.mods.size();
                this.scrollTarget = 0;
            }
            this.grab(Hold.MOVE, px, py);
        } else if (py < this.top + TITLE_H + TABS_H) {
            this.pickTab(px);
        }
        return true;
    }

    private void grab(Hold kind, double px, double py) {
        this.hold = kind;
        this.holdX = px;
        this.holdY = py;
        this.startLeft = this.left;
        this.startTop = this.top;
        this.startWidth = this.width;
        this.startHeight = this.height;
    }

    private void pickTab(double px) {
        int x = this.left + PAD;
        for (Tab each : Tab.values()) {
            int w = this.measureTab(each);
            if (px >= x - 4 && px < x + w + 4) {
                if (this.tab != each) {
                    this.tab = each;
                    this.scrollTarget = 0;
                    this.scroll.snap(0);
                }
                return;
            }
            x += w + 12;
        }
    }

    private int measureTab(Tab each) {
        return this.tabWidths.getOrDefault(each, 0);
    }

    @Override
    protected boolean onDrag(double px, double py, int button) {
        int dx = (int) Math.round(px - this.holdX);
        int dy = (int) Math.round(py - this.holdY);
        switch (this.hold) {
            case MOVE -> {
                this.left = this.startLeft + dx;
                this.top = this.startTop + dy;
            }
            case SIZE -> {
                this.width = this.startWidth + dx;
                this.height = this.startHeight + dy;
            }
            case NONE -> {
                return false;
            }
        }
        this.clampToScreen();
        return true;
    }

    @Override
    protected boolean onRelease(double px, double py, int button) {
        this.hold = Hold.NONE;
        return true;
    }

    @Override
    protected boolean onScroll(double amount) {
        this.scrollTarget -= (float) amount * 3 * LINE;
        return true;
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        float a = alpha * this.shown.getValue();
        int x0 = this.left;
        int y0 = this.top;
        int x1 = x0 + this.width;
        int y1 = y0 + this.height;
        canvas.fill(x0, y0, x1, y1, Colors.fade(Palette.PANEL, a));
        canvas.fill(x0, y0, x1, y0 + 2, Colors.fade(Palette.ACCENT, a));
        canvas.fill(x0, y1 - 1, x1, y1, Colors.fade(Palette.PANEL_EDGE, a));
        canvas.fill(x0, y0, x0 + 1, y1, Colors.fade(Palette.PANEL_EDGE, a));
        canvas.fill(x1 - 1, y0, x1, y1, Colors.fade(Palette.PANEL_EDGE, a));
        DevModView mod = this.findMod();
        String title = Lang.get("hotwire.dev.window.title");
        canvas.drawText(title, x0 + PAD, y0 + 7, Colors.fade(Palette.TEXT, a));
        if (mod != null) {
            String name = this.mods.size() > 1 ? mod.name() + " (" + (this.modIndex + 1) + "/" + this.mods.size() + ")"
                    : mod.name();
            int nameX = x0 + PAD + canvas.measureText(title) + 8;
            canvas.drawText(canvas.clip(name, x1 - PAD - nameX), nameX, y0 + 7, Colors.fade(Palette.ACCENT_SOFT, a));
        }
        this.paintTabs(canvas, a, x0, y0 + TITLE_H);
        canvas.fill(x0 + 1, y0 + TITLE_H + TABS_H - 1, x1 - 1, y0 + TITLE_H + TABS_H,
                Colors.fade(Palette.HAIRLINE, a));
        int bodyTop = y0 + TITLE_H + TABS_H;
        int bodyBottom = this.tab == Tab.OVERVIEW && mod != null ? y1 - BUTTON_H - 2 * PAD : y1 - 2;
        canvas.pushClip(x0 + 1, bodyTop, x1 - 1, bodyBottom);
        int y = bodyTop + 6 - Math.round(this.scroll.getValue());
        int startY = y;
        if (mod == null) {
            y = this.paintWrapped(canvas, Lang.get("hotwire.dev.window.no_mods"), x0 + PAD, y, Palette.DIM, a);
        } else {
            y = switch (this.tab) {
                case OVERVIEW -> this.paintOverview(canvas, mod, x0 + PAD, y, a);
                case HISTORY -> this.paintHistory(canvas, mod, x0 + PAD, y, a);
                case CRASHES -> this.paintCrashes(canvas, mod, x0 + PAD, y, a);
                case MIXINS -> this.paintMixins(canvas, mod, x0 + PAD, y, a);
            };
        }
        this.contentHeight = y - startY + 6;
        canvas.popClip();

        for (int i = 0; i < 3; i++) {
            int gx = x1 - 3 - i * 3;
            canvas.fill(gx, y1 - 3, gx + 1, y1 - 2, Colors.fade(Palette.FAINT, a));
            canvas.fill(x1 - 3, y1 - 3 - i * 3, x1 - 2, y1 - 2 - i * 3, Colors.fade(Palette.FAINT, a));
        }
    }

    private void paintTabs(Canvas canvas, float a, int x0, int y) {
        int x = x0 + PAD;
        for (Tab each : Tab.values()) {
            String name = Lang.get("hotwire.dev.tab." + each.name().toLowerCase(Locale.ROOT));
            int w = canvas.measureText(name);
            this.tabWidths.put(each, w);
            boolean chosen = each == this.tab;
            if (chosen) {
                this.tabLeft.to(x);
                this.tabWidth.to(w);
                if (this.tabWidth.getValue() == 0) {
                    this.tabLeft.snap(x);
                    this.tabWidth.snap(w);
                }
            }
            DevModView mod = this.findMod();
            int count = each == Tab.CRASHES && mod != null ? mod.crashes().size() : 0;
            String shown = count > 0 ? name + " " + count : name;
            canvas.drawText(shown, x, y + 3, Colors.fade(chosen ? Palette.TEXT : Palette.DIM, a));
            x += w + 12;
        }
        int lineX = Math.round(this.tabLeft.getValue());
        canvas.fill(lineX, y + TABS_H - 3, lineX + Math.round(this.tabWidth.getValue()), y + TABS_H - 1,
                Colors.fade(Palette.ACCENT, a));
    }

    private int paintOverview(Canvas canvas, DevModView mod, int x, int y, float a) {
        int stateColor = findColor(mod.state());
        canvas.fill(x, y + 2, x + 5, y + 7, Colors.fade(stateColor, a));
        String state = Lang.get("hotwire.dev.state." + mod.state().name().toLowerCase(Locale.ROOT));
        canvas.drawText(state, x + 9, y, Colors.fade(Palette.TEXT, a));
        String build = mod.runningBuild() == 0 ? Lang.get("hotwire.dev.window.started_with")
                : Lang.get("hotwire.dev.window.build", mod.runningBuild(), describeAgo(mod.builtAt()));
        canvas.drawText(build, x + 9 + canvas.measureText(state) + 8, y, Colors.fade(Palette.FAINT, a));
        y += LINE + 2;
        if (!mod.line().isEmpty()) {
            y = this.paintWrapped(canvas, mod.line(), x + 9, y, Palette.DIM, a);
        }
        y += 6;
        if (!mod.events().isEmpty()) {
            DevEvent last = mod.events().getFirst();
            canvas.drawText(Lang.get("hotwire.dev.window.last"), x, y, Colors.fade(Palette.FAINT, a));
            y += LINE;
            canvas.fill(x, y + 2, x + 5, y + 7, Colors.fade(findColor(last.kind()), a));
            canvas.drawText(canvas.clip(last.title() + "  " + describeAgo(last.at()), this.width - 2 * PAD - 9),
                    x + 9, y, Colors.fade(Palette.TEXT, a));
            y += LINE;
            for (String line : last.lines().subList(0, Math.min(3, last.lines().size()))) {
                canvas.drawText(canvas.clip(line, this.width - 2 * PAD - 9), x + 9, y, Colors.fade(Palette.DIM, a));
                y += LINE;
            }
            y += 6;
        }
        return this.paintWrapped(canvas, this.runtime, x, y, this.fullRuntime ? Palette.GOOD : Palette.OFF_TINT, a);
    }

    private int paintHistory(Canvas canvas, DevModView mod, int x, int y, float a) {
        if (mod.events().isEmpty()) {
            return this.paintWrapped(canvas, Lang.get("hotwire.dev.window.no_history"), x, y, Palette.DIM, a);
        }
        int textWidth = this.width - 2 * PAD - 9;
        for (DevEvent event : mod.events()) {
            String clock = LocalTime.ofInstant(Instant.ofEpochMilli(event.at()), ZoneId.systemDefault()).format(CLOCK);
            canvas.fill(x, y + 2, x + 5, y + 7, Colors.fade(findColor(event.kind()), a));
            canvas.drawText(clock, x + 9, y, Colors.fade(Palette.FAINT, a));
            int titleX = x + 9 + canvas.measureText(clock) + 6;
            canvas.drawText(canvas.clip(event.title(), x + 9 + textWidth - titleX), titleX, y,
                    Colors.fade(Palette.TEXT, a));
            y += LINE;
            for (String line : event.lines()) {
                canvas.drawText(canvas.clip(line, textWidth), x + 9, y, Colors.fade(Palette.DIM, a));
                y += LINE;
            }
            y += 4;
        }
        return y;
    }

    private int paintCrashes(Canvas canvas, DevModView mod, int x, int y, float a) {
        if (mod.crashes().isEmpty()) {
            return this.paintWrapped(canvas, Lang.get("hotwire.dev.window.no_crashes"), x, y, Palette.DIM, a);
        }
        int textWidth = this.width - 2 * PAD;
        for (DevCrash crash : mod.crashes()) {
            String times = crash.count() == 1 ? "" : "x" + crash.count() + "  ";
            canvas.drawText(times, x, y, Colors.fade(Palette.ACCENT, a));
            int whatX = x + canvas.measureText(times);
            canvas.drawText(canvas.clip(crash.what(), x + textWidth - whatX), whatX, y, Colors.fade(Palette.TEXT, a));
            y += LINE;
            canvas.drawText(canvas.clip(crash.where() + "  " + describeAgo(crash.lastAt()), textWidth), x, y,
                    Colors.fade(Palette.DIM, a));
            y += LINE + 5;
        }
        return y;
    }

    private int paintMixins(Canvas canvas, DevModView mod, int x, int y, float a) {
        if (mod.mixins().isEmpty()) {
            return this.paintWrapped(canvas, Lang.get("hotwire.dev.window.no_mixins"), x, y, Palette.DIM, a);
        }
        int textWidth = this.width - 2 * PAD;
        for (DevMixin mixin : mod.mixins()) {
            canvas.drawText(canvas.clip(mixin.target(), textWidth), x, y, Colors.fade(Palette.TEXT, a));
            y += LINE;
            String others = mixin.others().isEmpty() ? Lang.get("hotwire.dev.mixin.alone")
                    : Lang.get("hotwire.dev.mixin.also", String.join(", ", mixin.others()));
            y = this.paintWrapped(canvas, others, x + 9, y, mixin.others().isEmpty() ? Palette.FAINT : Palette.WARN, a);
            y += 4;
        }
        return y;
    }

    private int paintWrapped(Canvas canvas, String text, int x, int y, int color, float a) {
        int room = this.left + this.width - PAD - x;
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ", -1)) {
            String tried = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && canvas.measureText(tried) > room) {
                canvas.drawText(line.toString(), x, y, Colors.fade(color, a));
                y += LINE;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(tried);
            }
        }
        if (!line.isEmpty()) {
            canvas.drawText(line.toString(), x, y, Colors.fade(color, a));
            y += LINE;
        }
        return y;
    }

    private static int findColor(DevState state) {
        return switch (state) {
            case UP_TO_DATE -> Palette.GOOD;
            case LOADING, WAITING -> Palette.WARN;
            case NEEDS_RESTART -> Palette.ON;
            case FAILED -> Palette.BAD;
        };
    }

    private static int findColor(DevEvent.Kind kind) {
        return switch (kind) {
            case IN, RESTARTED, ASSETS -> Palette.GOOD;
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
