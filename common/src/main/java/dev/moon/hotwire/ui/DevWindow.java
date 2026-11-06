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
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

public final class DevWindow extends Element {
    private static final int TITLE_H = 22;

    private static final int CARDS_WIDTH = 220;
    private static final int CARDS_REACH = 60;
    private static final int PAD = 8;
    private static final int LINE = 10;
    private static final int GAP = 4;
    private static final int MIN_W = 260;
    private static final int MIN_H = 170;
    private static final int BUTTON = 14;
    private static final int BUTTON_H = 18;
    private static final int CARD = 0xFF1A1427;
    private static final float REFRESH_SECONDS = 0.25f;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    private enum Tab { OVERVIEW, HISTORY, CRASHES, MIXINS }

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

    private final List<CrashRow> crashRows = new ArrayList<>();

    private record CrashRow(int top, int bottom, DevCrash crash) {
    }
    private Tab tab = Tab.OVERVIEW;
    private final WindowFrame frame = new WindowFrame(MIN_W, MIN_H, TITLE_H);
    private final double @Nullable [] saved;
    private final Consumer<double[]> onPlaced;
    private final Runnable onClose;
    private int contentHeight;

    @SuppressWarnings("this-escape")
    public DevWindow(DevHost host, String runtime, boolean fullRuntime, double @Nullable [] saved,
            Consumer<double[]> onPlaced, Runnable onClose) {
        this.host = host;
        this.runtime = runtime;
        this.fullRuntime = fullRuntime;
        this.saved = saved == null ? null : saved.clone();
        this.onPlaced = onPlaced;
        this.onClose = onClose;
        this.tabs = this.add(new ChoiceBar(makeTabs(0), 0, this::pickTab));
        this.reload = this.add(new Button(Lang.get("hotwire.dev.button.reload"), false, () -> this.act(true)));
        this.restart = this.add(new Button(Lang.get("hotwire.dev.button.restart"),
                Lang.get("hotwire.dev.button.restart.short"), false, () -> this.act(false)));
        this.pause = this.add(new Button(Lang.get("hotwire.dev.button.pause"),
                Lang.get("hotwire.dev.button.pause.short"), false, () -> host.setWatching(false)));
        this.resume = this.add(new Button(Lang.get("hotwire.dev.button.resume"),
                Lang.get("hotwire.dev.button.resume.short"), false, () -> host.setWatching(true)));
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

    public int findCardsTop(int screenWidth) {
        boolean underCards = this.frame.getY() < CARDS_REACH && this.frame.getX() + this.frame.getWidth() > screenWidth
                - CARDS_WIDTH;
        return underCards ? this.frame.getY() + TITLE_H : 0;
    }

    public int[] getFrameBounds() {
        return this.frame.getBounds();
    }

    private @Nullable DevModView findMod() {
        return this.mods.isEmpty() ? null : this.mods.get(Math.min(this.modIndex, this.mods.size() - 1));
    }

    public void setScreen(int width, int height) {
        this.frame.setScreen(width, height, this.saved, Math.clamp(width * 2 / 5, Math.min(MIN_W, width), 340),
                Math.clamp(height * 3 / 4, Math.min(MIN_H, height), 280), 8, Math.max(0, Math.min(36, height / 8)));
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
        this.frame.step(dt);
        int left = this.frame.getX();
        int top = this.frame.getY();
        int width = this.frame.getWidth();
        int height = this.frame.getHeight();
        this.scrollTarget = Math.clamp(this.scrollTarget, 0, Math.max(0, this.contentHeight - this.findBodyRoom()));
        this.scroll.to(this.scrollTarget);
        this.scroll.step(dt);
        this.setBounds(left, top, width, height);
        this.tabs.setBounds(left + PAD, top + TITLE_H, width - 2 * PAD, ChoiceBar.HEIGHT);
        boolean watching = this.host.isWatching();
        boolean overview = this.tab == Tab.OVERVIEW && this.findMod() != null;
        int buttonsY = top + height - BUTTON_H - PAD;
        int buttonW = (width - 4 * PAD) / 3;
        this.reload.setVisible(overview);
        this.restart.setVisible(overview);
        this.pause.setVisible(overview && watching);
        this.resume.setVisible(overview && !watching);
        this.reload.setBounds(left + PAD, buttonsY, buttonW, BUTTON_H);
        this.restart.setBounds(left + 2 * PAD + buttonW, buttonsY, buttonW, BUTTON_H);
        this.pause.setBounds(left + 3 * PAD + 2 * buttonW, buttonsY, buttonW, BUTTON_H);
        this.resume.setBounds(left + 3 * PAD + 2 * buttonW, buttonsY, buttonW, BUTTON_H);
        super.step(dt);
    }

    private int findBodyTop() {
        return this.frame.getY() + TITLE_H + ChoiceBar.HEIGHT + 6;
    }

    private int findBodyBottom() {
        boolean buttons = this.tab == Tab.OVERVIEW && this.findMod() != null;
        return this.frame.getY() + this.frame.getHeight() - (buttons ? BUTTON_H + 2 * PAD + 1 : 4);
    }

    private int findCloseX() {
        return this.frame.getX() + this.frame.getWidth() - PAD - BUTTON;
    }

    private int findMaximizeX() {
        return this.findCloseX() - BUTTON - 2;
    }

    private boolean isOnButton(double px, double py, int x) {
        int y = this.frame.getY() + 4;
        return px >= x && px < x + BUTTON && py >= y && py < y + BUTTON;
    }

    private int findBodyRoom() {
        return this.findBodyBottom() - this.findBodyTop();
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        if (button != 0) {
            return true;
        }
        if (this.isOnButton(px, py, this.findCloseX())) {
            this.onClose.run();
            return true;
        }
        if (this.isOnButton(px, py, this.findMaximizeX())) {
            this.frame.toggleMaximized();
            this.onPlaced.accept(this.frame.save());
            return true;
        }
        DevModView shown = this.findMod();
        if (shown != null && py >= this.findBodyTop() && py < this.findBodyBottom()) {
            for (CrashRow row : this.crashRows) {
                if (py >= row.top() && py < row.bottom() && !row.crash().source().isEmpty()) {
                    this.host.openSource(shown.id(), row.crash());
                    return true;
                }
            }
        }

        boolean onName = py < this.frame.getY() + TITLE_H && px < this.frame.getX() + this.frame.getWidth() / 2.0;
        if (this.mods.size() > 1 && onName && this.frame.findEdges(px, py) == 0) {
            this.modIndex = (this.modIndex + 1) % this.mods.size();
            this.scrollTarget = 0;
            this.sinceRead = REFRESH_SECONDS;
        }
        if (this.frame.press(px, py, System.nanoTime()) && !this.frame.isGrabbed()) {
            this.onPlaced.accept(this.frame.save());
        }
        return true;
    }

    @Override
    protected boolean onDrag(double px, double py, int button) {
        if (!this.frame.isGrabbed()) {
            return false;
        }
        this.frame.drag(px, py);
        return true;
    }

    @Override
    protected boolean onRelease(double px, double py, int button) {
        if (this.frame.release()) {
            this.onPlaced.accept(this.frame.save());
        }
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
        int x0 = this.frame.getX();
        int y0 = this.frame.getY();
        int x1 = x0 + this.frame.getWidth();
        int y1 = y0 + this.frame.getHeight();
        int[] preview = this.frame.findPreview();
        if (preview != null) {
            int px1 = preview[0] + preview[2];
            int py1 = preview[1] + preview[3];
            canvas.fill(preview[0], preview[1], px1, py1, Colors.fade(Palette.ACCENT, a * 0.12f));
            canvas.fill(preview[0], preview[1], px1, preview[1] + 1, Colors.fade(Palette.ACCENT, a * 0.6f));
            canvas.fill(preview[0], py1 - 1, px1, py1, Colors.fade(Palette.ACCENT, a * 0.6f));
            canvas.fill(preview[0], preview[1], preview[0] + 1, py1, Colors.fade(Palette.ACCENT, a * 0.6f));
            canvas.fill(px1 - 1, preview[1], px1, py1, Colors.fade(Palette.ACCENT, a * 0.6f));
        }
        Canvas.Cursor cursor = this.frame.findCursor(this.getPointerX(), this.getPointerY(), this.isHovered());
        if (cursor != null) {
            canvas.requestCursor(cursor);
        }
        canvas.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, Colors.fade(Palette.PANEL_EDGE, a));
        canvas.fill(x0, y0, x1, y1, Colors.fade(Palette.PANEL, a));
        canvas.fill(x0, y0, x1, y0 + 2, Colors.fade(Palette.ACCENT, a));
        DevModView mod = this.findMod();
        String title = Lang.get("hotwire.dev.window.title");
        canvas.drawText(title, x0 + PAD, y0 + 8, Colors.fade(Palette.TEXT, a));
        this.paintButtons(canvas, a);
        if (mod != null) {
            int pillLeft = paintPill(canvas, mod.state(), this.findMaximizeX() - 6, y0 + 5, a);
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
        this.crashRows.clear();
        int cardX = x0 + PAD;
        int cardW = this.frame.getWidth() - 2 * PAD;
        if (mod == null) {
            y = paintText(canvas, Lang.get("hotwire.dev.window.no_mods"), cardX, y, cardW, Palette.DIM, a);
        } else {
            y = switch (this.tab) {
                case OVERVIEW -> this.paintOverview(canvas, mod, cardX, y, cardW, a);
                case HISTORY -> paintHistory(canvas, mod, cardX, y, cardW, a);
                case CRASHES -> this.paintCrashes(canvas, mod, cardX, y, cardW, a);
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

        if (!this.frame.isMaximized()) {
            for (int i = 0; i < 3; i++) {
                canvas.fill(x1 - 3 - i * 3, y1 - 3, x1 - 2 - i * 3, y1 - 2, Colors.fade(Palette.FAINT, a));
                canvas.fill(x1 - 3, y1 - 3 - i * 3, x1 - 2, y1 - 2 - i * 3, Colors.fade(Palette.FAINT, a));
            }
        }
    }

    private void paintButtons(Canvas canvas, float a) {
        int y = this.frame.getY() + 4;
        boolean over = this.isHovered();
        int maxX = this.findMaximizeX();
        int closeX = this.findCloseX();
        if (over && this.isOnButton(this.getPointerX(), this.getPointerY(), maxX)) {
            canvas.fill(maxX, y, maxX + BUTTON, y + BUTTON, Colors.fade(0x30B48CFF, a));
        }
        boolean onClose = over && this.isOnButton(this.getPointerX(), this.getPointerY(), closeX);
        if (onClose) {
            canvas.fill(closeX, y, closeX + BUTTON, y + BUTTON, Colors.fade(Palette.BAD, a * 0.85f));
        }
        int ink = Colors.fade(Palette.DIM, a);
        if (this.frame.isMaximized()) {
            paintBox(canvas, maxX + 5, y + 3, 6, 6, ink);
            paintBox(canvas, maxX + 3, y + 5, 6, 6, ink);
            canvas.fill(maxX + 4, y + 6, maxX + 8, y + 10, Colors.fade(Palette.PANEL, a));
        } else {
            paintBox(canvas, maxX + 3, y + 3, 8, 8, ink);
        }
        int cross = Colors.fade(onClose ? Palette.TEXT : Palette.DIM, a);
        for (int i = 0; i < 7; i++) {
            canvas.fill(closeX + 4 + i, y + 4 + i, closeX + 5 + i, y + 5 + i, cross);
            canvas.fill(closeX + 10 - i, y + 4 + i, closeX + 11 - i, y + 5 + i, cross);
        }
    }

    private static void paintBox(Canvas canvas, int x, int y, int w, int h, int ink) {
        canvas.fill(x, y, x + w, y + 1, ink);
        canvas.fill(x, y + h - 1, x + w, y + h, ink);
        canvas.fill(x, y, x + 1, y + h, ink);
        canvas.fill(x + w - 1, y, x + w, y + h, ink);
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
            y = this.paintCrash(canvas, crash, describeAgo(crash.lastAt()), x, y, w, a);
        }

        int runtimeColor = this.fullRuntime ? Palette.GOOD : Palette.ACCENT;
        return paintCard(canvas, x, y, w, runtimeColor, Lang.get("hotwire.dev.window.runtime"), "",
                List.of(this.runtime), Palette.DIM, a);
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

    private int paintCrashes(Canvas canvas, DevModView mod, int x, int y, int w, float a) {
        if (mod.crashes().isEmpty()) {
            return paintText(canvas, Lang.get("hotwire.dev.window.no_crashes"), x, y, w, Palette.DIM, a);
        }
        for (DevCrash crash : mod.crashes()) {
            String when = crash.count() == 1 ? describeAgo(crash.lastAt())
                    : "x" + crash.count() + ", " + describeAgo(crash.lastAt());
            y = this.paintCrash(canvas, crash, when, x, y, w, a);
        }
        return y;
    }

    private int paintCrash(Canvas canvas, DevCrash crash, String when, int x, int y, int w, float a) {
        List<String> lines = crash.source().isEmpty() ? List.of(crash.where())
                : List.of(crash.where(), Lang.get("hotwire.dev.crash.open"));
        int bottom = paintCard(canvas, x, y, w, Palette.BAD, crash.what(), when, lines, Palette.DIM, a);
        this.crashRows.add(new CrashRow(y, bottom, crash));
        return bottom;
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
