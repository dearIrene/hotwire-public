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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

public final class DevWindow extends Element {
    private static final int TITLE_H = 22;
    private static final int PAD = 8;
    private static final int GRIP = 10;
    private static final int LINE = 10;
    private static final int GAP = 4;
    private static final int MIN_W = 260;
    private static final int MIN_H = 170;
    private static final int MARGIN = 4;
    private static final int BUTTON_H = 18;
    private static final int CARD = 0xFF1A1427;
    private static final float REFRESH_SECONDS = 0.25f;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    private enum Tab { OVERVIEW, HISTORY, CRASHES, MIXINS }

    private enum Hold { NONE, MOVE, SIZE }

    private final DevHost host;
    private final String runtime;
    private final boolean fullRuntime;
    private final EasedValue shown = EasedValue.ofSpeed(14);
    private final EasedValue scroll = EasedValue.ofSpeed(14);
    private final ChoiceBar tabs;
    private final Button reload;
    private final Button restart;
    private final Button pause;
    private final Button resume;
    private float scrollTarget;
    private float sinceRead = REFRESH_SECONDS;
    private List<DevModView> mods = List.of();
    private int modIndex;
    private int crashCount = -1;
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
    private int height = 220;
    private int screenWidth = 854;
    private int screenHeight = 480;
    private int contentHeight;

    @SuppressWarnings("this-escape")
    public DevWindow(DevHost host, String runtime, boolean fullRuntime) {
        this.host = host;
        this.runtime = runtime;
        this.fullRuntime = fullRuntime;
        this.tabs = this.add(new ChoiceBar(makeTabs(0), 0, this::pickTab));
        this.reload = this.add(new Button(Lang.get("hotwire.dev.button.reload"), false, () -> this.act(true)));
        this.restart = this.add(new Button(Lang.get("hotwire.dev.button.restart"), false, () -> this.act(false)));
        this.pause = this.add(new Button(Lang.get("hotwire.dev.button.pause"), false, () -> host.setWatching(false)));
        this.resume = this.add(new Button(Lang.get("hotwire.dev.button.resume"), false, () -> host.setWatching(true)));
        this.shown.snap(0);
    }

    private static List<ChoiceBar.Choice> makeTabs(int crashes) {
        List<ChoiceBar.Choice> made = new ArrayList<>();
        for (Tab each : Tab.values()) {
            String label = Lang.get("hotwire.dev.tab." + each.name().toLowerCase(Locale.ROOT));
            made.add(new ChoiceBar.Choice(label, each == Tab.CRASHES && crashes > 0 ? String.valueOf(crashes) : null,
                    ChoiceBar.Glyph.NONE));
        }
        return made;
    }

    private void pickTab(int index) {
        this.tab = Tab.values()[index];
        this.scrollTarget = 0;
        this.scroll.snap(0);
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
            DevModView mod = this.findMod();
            int crashes = mod == null ? 0 : mod.crashes().size();
            if (crashes != this.crashCount) {
                this.crashCount = crashes;
                this.tabs.setChoices(makeTabs(crashes));
            }
        }
        this.scrollTarget = Math.clamp(this.scrollTarget, 0, Math.max(0, this.contentHeight - this.findBodyRoom()));
        this.scroll.to(this.scrollTarget);
        this.scroll.step(dt);
        this.setBounds(this.left, this.top, this.width, this.height);
        this.tabs.setBounds(this.left + PAD, this.top + TITLE_H, this.width - 2 * PAD, ChoiceBar.HEIGHT);
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

    private int findBodyTop() {
        return this.top + TITLE_H + ChoiceBar.HEIGHT + 6;
    }

    private int findBodyBottom() {
        boolean buttons = this.tab == Tab.OVERVIEW && this.findMod() != null;
        return this.top + this.height - (buttons ? BUTTON_H + 2 * PAD + 1 : 4);
    }

    private int findBodyRoom() {
        return this.findBodyBottom() - this.findBodyTop();
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
                this.sinceRead = REFRESH_SECONDS;
            }
            this.grab(Hold.MOVE, px, py);
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
        canvas.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, Colors.fade(Palette.PANEL_EDGE, a));
        canvas.fill(x0, y0, x1, y1, Colors.fade(Palette.PANEL, a));
        canvas.fill(x0, y0, x1, y0 + 2, Colors.fade(Palette.ACCENT, a));
        DevModView mod = this.findMod();
        String title = Lang.get("hotwire.dev.window.title");
        canvas.drawText(title, x0 + PAD, y0 + 8, Colors.fade(Palette.TEXT, a));
        if (mod != null) {
            int pillLeft = paintPill(canvas, mod.state(), x1 - PAD, y0 + 5, a);
            String name = this.mods.size() > 1 ? mod.name() + " (" + (this.modIndex + 1) + "/" + this.mods.size() + ")"
                    : mod.name();
            int nameX = x0 + PAD + canvas.measureText(title) + 6;
            canvas.drawText(canvas.clip(name, Math.max(1, pillLeft - 6 - nameX)), nameX, y0 + 8,
                    Colors.fade(Palette.ACCENT_SOFT, a));
        }
        int bodyTop = this.findBodyTop();
        int bodyBottom = this.findBodyBottom();
        canvas.pushClip(x0 + 1, bodyTop, x1 - 1, bodyBottom);
        int y = bodyTop - Math.round(this.scroll.getValue());
        int startY = y;
        int cardX = x0 + PAD;
        int cardW = this.width - 2 * PAD;
        if (mod == null) {
            y = paintText(canvas, Lang.get("hotwire.dev.window.no_mods"), cardX, y, cardW, Palette.DIM, a);
        } else {
            y = switch (this.tab) {
                case OVERVIEW -> this.paintOverview(canvas, mod, cardX, y, cardW, a);
                case HISTORY -> paintHistory(canvas, mod, cardX, y, cardW, a);
                case CRASHES -> paintCrashes(canvas, mod, cardX, y, cardW, a);
                case MIXINS -> paintMixins(canvas, mod, cardX, y, cardW, a);
            };
        }
        this.contentHeight = y - startY;
        canvas.popClip();
        SettingsPanel.fadeEdges(canvas, x0 + 1, bodyTop, x1 - 1, bodyBottom, this.scroll.getValue() > 1,
                this.scroll.getValue() < this.contentHeight - (bodyBottom - bodyTop) - 1, a);
        if (this.tab == Tab.OVERVIEW && mod != null) {
            canvas.fill(x0 + PAD, bodyBottom, x1 - PAD, bodyBottom + 1, Colors.fade(Palette.HAIRLINE, a));
        }
        int room = bodyBottom - bodyTop;
        if (this.contentHeight > room) {
            int thumb = Math.max(10, room * room / this.contentHeight);
            int offset = Math.round((room - thumb) * this.scroll.getValue() / (this.contentHeight - room));
            canvas.fill(x1 - 4, bodyTop + offset, x1 - 2, bodyTop + offset + thumb, Colors.fade(Palette.PANEL_EDGE,
                    a));
        }

        for (int i = 0; i < 3; i++) {
            canvas.fill(x1 - 3 - i * 3, y1 - 3, x1 - 2 - i * 3, y1 - 2, Colors.fade(Palette.FAINT, a));
            canvas.fill(x1 - 3, y1 - 3 - i * 3, x1 - 2, y1 - 2 - i * 3, Colors.fade(Palette.FAINT, a));
        }
    }

    private static int paintPill(Canvas canvas, DevState state, int right, int y, float a) {
        String word = Lang.get("hotwire.dev.state." + state.name().toLowerCase(Locale.ROOT));
        int x = right - canvas.measureText(word) - 18;
        canvas.fill(x, y, right, y + 14, Colors.fade(Palette.PANEL_EDGE, a));
        canvas.fill(x + 1, y + 1, right - 1, y + 13, Colors.fade(CARD, a));
        canvas.fill(x + 5, y + 5, x + 9, y + 9, Colors.fade(findColor(state), a));
        canvas.drawText(word, x + 12, y + 3, Colors.fade(Palette.TEXT, a));
        return x;
    }

    private static int paintCard(Canvas canvas, int x, int y, int w, int stripe, String heading, String aside,
            List<String> lines, int lineColor, float a) {
        int textX = x + PAD + 2;
        int textW = Math.max(1, w - 2 * PAD - 2);
        int asideW = aside.isEmpty() ? 0 : canvas.measureText(aside) + 6;
        List<String> head = wrap(canvas, heading, Math.max(1, textW - asideW));
        List<String> wrapped = new ArrayList<>();
        lines.forEach(line -> wrapped.addAll(wrap(canvas, line, textW)));
        int h = 2 * PAD - 2 + head.size() * LINE + wrapped.size() * LINE + (wrapped.isEmpty() ? 0 : 2);
        canvas.fill(x, y, x + w, y + h, Colors.fade(Palette.PANEL_EDGE, a));
        canvas.fill(x + 1, y + 1, x + w - 1, y + h - 1, Colors.fade(CARD, a));
        canvas.fill(x + 1, y + 1, x + 3, y + h - 1, Colors.fade(stripe, a));
        int lineY = y + PAD;
        for (String line : head) {
            canvas.drawText(line, textX, lineY, Colors.fade(Palette.TEXT, a));
            lineY += LINE;
        }
        if (!aside.isEmpty()) {
            canvas.drawText(aside, x + w - PAD - canvas.measureText(aside), y + PAD, Colors.fade(Palette.FAINT, a));
        }
        lineY += 2;
        for (String line : wrapped) {
            canvas.drawText(line, textX, lineY, Colors.fade(lineColor, a));
            lineY += LINE;
        }
        return y + h + GAP;
    }

    private int paintOverview(Canvas canvas, DevModView mod, int x, int y, int w, float a) {
        String build = mod.runningBuild() == 0 ? Lang.get("hotwire.dev.window.started_with")
                : Lang.get("hotwire.dev.window.build", mod.runningBuild(), describeAgo(mod.builtAt()));
        if (mod.events().isEmpty()) {
            y = paintCard(canvas, x, y, w, findColor(mod.state()), build, "", mod.line().isEmpty() ? List.of()
                    : List.of(mod.line()), Palette.DIM, a);
        } else {
            DevEvent last = mod.events().getFirst();
            List<String> lines = new ArrayList<>(last.lines());
            if (!mod.line().isEmpty()) {
                lines.add(mod.line());
            }
            lines.add(build);
            y = paintCard(canvas, x, y, w, findColor(last.kind()), last.title(), describeAgo(last.at()), lines,
                    Palette.DIM, a);
        }
        if (!mod.crashes().isEmpty()) {
            DevCrash crash = mod.crashes().getFirst();
            y = paintCard(canvas, x, y, w, Palette.BAD, crash.what(), describeAgo(crash.lastAt()),
                    List.of(crash.where()), Palette.DIM, a);
        }
        int runtimeColor = this.fullRuntime ? Palette.GOOD : Palette.OFF_TINT;
        return paintCard(canvas, x, y, w, runtimeColor, Lang.get("hotwire.dev.window.runtime"), "",
                List.of(this.runtime), runtimeColor, a);
    }

    private static int paintHistory(Canvas canvas, DevModView mod, int x, int y, int w, float a) {
        if (mod.events().isEmpty()) {
            return paintText(canvas, Lang.get("hotwire.dev.window.no_history"), x, y, w, Palette.DIM, a);
        }
        for (DevEvent event : mod.events()) {
            String clock = LocalTime.ofInstant(Instant.ofEpochMilli(event.at()), ZoneId.systemDefault()).format(CLOCK);
            y = paintCard(canvas, x, y, w, findColor(event.kind()), event.title(), clock, event.lines(), Palette.DIM,
                    a);
        }
        return y;
    }

    private static int paintCrashes(Canvas canvas, DevModView mod, int x, int y, int w, float a) {
        if (mod.crashes().isEmpty()) {
            return paintText(canvas, Lang.get("hotwire.dev.window.no_crashes"), x, y, w, Palette.DIM, a);
        }
        for (DevCrash crash : mod.crashes()) {
            String when = crash.count() == 1 ? describeAgo(crash.lastAt())
                    : "x" + crash.count() + ", " + describeAgo(crash.lastAt());
            y = paintCard(canvas, x, y, w, Palette.BAD, crash.what(), when, List.of(crash.where()), Palette.DIM, a);
        }
        return y;
    }

    private static int paintMixins(Canvas canvas, DevModView mod, int x, int y, int w, float a) {
        if (mod.mixins().isEmpty()) {
            return paintText(canvas, Lang.get("hotwire.dev.window.no_mixins"), x, y, w, Palette.DIM, a);
        }
        for (DevMixin mixin : mod.mixins()) {
            boolean shared = !mixin.others().isEmpty();
            String line = shared ? Lang.get("hotwire.dev.mixin.also", String.join(", ", mixin.others()))
                    : Lang.get("hotwire.dev.mixin.alone");
            y = paintCard(canvas, x, y, w, shared ? Palette.WARN : Palette.FAINT, mixin.target(), "", List.of(line),
                    shared ? Palette.WARN : Palette.FAINT, a);
        }
        return y;
    }

    private static int paintText(Canvas canvas, String text, int x, int y, int w, int color, float a) {
        for (String line : wrap(canvas, text, w)) {
            canvas.drawText(line, x, y + 4, Colors.fade(color, a));
            y += LINE;
        }
        return y + 8;
    }

    private static List<String> wrap(Canvas canvas, String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ", -1)) {
            if (word.isEmpty()) {
                continue;
            }
            String tried = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && canvas.measureText(tried) > width) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(tried);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
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
