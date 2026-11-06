package dev.moon.hotwire.ui;

import dev.moon.hotwire.dev.DevCrash;
import dev.moon.hotwire.dev.DevEvent;
import dev.moon.hotwire.dev.DevHost;
import dev.moon.hotwire.dev.DevModView;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.jspecify.annotations.Nullable;

public final class DevWindow extends Element {
    private static final int TITLE_H = 22;

    private static final int STEPPER_W = 44;

    private static final int CARDS_WIDTH = 220;
    private static final int CARDS_REACH = 60;
    private static final int PAD = 8;
    private static final int LINE = 10;
    private static final int MIN_W = 260;
    private static final int MIN_H = 170;
    private static final int BUTTON = 14;
    private static final int BUTTON_H = 18;
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
    private final Button back;
    private final Button pause;
    private final Button resume;

    private final BuildPicker picker;
    private final Button confirm;
    private final Button merge;
    private final Button cancel;
    private final TextField naming;
    private float scrollTarget;
    private float sinceRead = REFRESH_SECONDS;
    private List<DevModView> mods = List.of();
    private int modIndex;
    private int crashCount = -1;

    private final List<CrashRow> crashRows = new ArrayList<>();

    private boolean mcpInstalled;
    private Runnable getMcp = () -> { };
    private int @Nullable [] mcpRow;

    private final List<BuildRow> buildRows = new ArrayList<>();

    private record BuildRow(int top, int bottom, int build) {
    }

    private record CrashRow(int top, int bottom, DevCrash crash) {
    }
    private Tab tab = Tab.OVERVIEW;
    private final WindowFrame frame = new WindowFrame(MIN_W, MIN_H, TITLE_H);
    private final double @Nullable [] saved;
    private final Consumer<double[]> onPlaced;

    private int scale;
    private final IntConsumer onScale;
    private int gameScale = 1;
    private int mostScale = 1;
    private final Runnable onClose;
    private int contentHeight;

    @SuppressWarnings("this-escape")
    public DevWindow(DevHost host, String runtime, boolean fullRuntime, double @Nullable [] saved,
            Consumer<double[]> onPlaced, int scale, IntConsumer onScale, Runnable onClose) {
        this.host = host;
        this.runtime = runtime;
        this.fullRuntime = fullRuntime;
        this.saved = saved == null ? null : saved.clone();
        this.onPlaced = onPlaced;
        this.scale = Math.max(0, scale);
        this.onScale = onScale;
        this.onClose = onClose;
        this.tabs = this.add(new ChoiceBar(makeTabs(0), 0, this::pickTab));
        this.reload = this.add(new Button(Lang.get("hotwire.dev.button.reload"),
                Lang.get("hotwire.dev.button.reload.short"), false, () -> this.act(true)));
        this.back = this.add(new Button(Lang.get("hotwire.dev.button.back"), Lang.get("hotwire.dev.button.back.short"),
                false, () -> this.openPicker(-1)));
        this.picker = new BuildPicker(host);
        this.confirm = this.add(new Button(Lang.get("hotwire.dev.button.back"),
                Lang.get("hotwire.dev.button.back.short"), true, () -> this.picker.confirm(this.findMod())));
        this.merge = this.add(new Button(Lang.get("hotwire.dev.button.merge"), false, this.picker::startMerge));
        this.cancel = this.add(new Button(Lang.get("hotwire.dev.button.cancel"), false, this.picker::cancel));
        this.naming = this.add(new TextField(Lang.get("hotwire.dev.name_hint"), text -> { },
                () -> {
                    this.picker.finishNaming(this.findMod());
                    this.sinceRead = REFRESH_SECONDS;
                }, this.picker::cancel));
        this.naming.setVisible(false);
        this.picker.setNamingField(this.naming);
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

    private void openPicker(int build) {
        DevModView mod = this.findMod();
        if (mod == null || mod.savedBuilds().isEmpty()) {
            return;
        }
        this.picker.open(mod, build);

        this.tabs.setChosen(0);
        this.tab = Tab.OVERVIEW;
        this.scrollTarget = 0;
    }

    private void pickTab(int index) {
        this.picker.close();
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

    public int getScale() {
        return this.scale;
    }

    public void setScaleRange(int game, int most) {
        this.gameScale = Math.max(1, game);
        this.mostScale = Math.max(1, most);
    }

    private int findShownScale() {
        return Math.min(this.scale == 0 ? this.gameScale : this.scale, this.mostScale);
    }

    public int @Nullable [] findBuildRowForTests(int build) {
        for (BuildRow row : this.buildRows) {
            if (row.build() == build) {
                return new int[] {row.top(), row.bottom()};
            }
        }
        return null;
    }

    public void showTabForTests(int index) {
        this.tabs.setChosen(index);
        this.pickTab(index);
    }

    public boolean pressRollBackForTests() {
        if (!this.back.isVisible()) {
            return false;
        }
        this.openPicker(-1);
        return true;
    }

    public boolean confirmPickForTests() {
        boolean open = this.picker.isOpen();
        this.picker.confirm(this.findMod());
        return open;
    }

    public boolean startMergeForTests() {
        boolean open = this.picker.isOpen();
        if (open) {
            this.picker.startMerge();
        }
        return open;
    }

    public void pressBuildForTests(int build) {
        this.picker.pressRowForTests(build);
    }

    public void pinForTests() {
        DevModView mod = this.findMod();
        if (mod != null && this.picker.isOpen()) {
            this.picker.pin(mod);
        }
    }

    public void nameForTests(String name) {
        DevModView mod = this.findMod();
        if (mod != null && this.picker.isOpen()) {
            this.host.rename(mod.id(), this.picker.getPicked(), name);
        }
    }

    public void cancelPickForTests() {
        this.picker.cancel();
    }

    public void copyForTests() {
        DevModView mod = this.findMod();
        if (mod != null && this.picker.isOpen()) {
            this.picker.copy(mod);
        }
    }

    public int[] getStepperBounds() {
        return new int[] {this.findStepperX(), this.frame.getY() + 4, STEPPER_W, BUTTON};
    }

    private int findStepperX() {
        return this.findMaximizeX() - 6 - STEPPER_W;
    }

    private boolean pressStepper(double px, double py) {
        int x = this.findStepperX();
        int y = this.frame.getY() + 4;
        if (py < y || py >= y + BUTTON || px < x || px >= x + STEPPER_W) {
            return false;
        }
        int step = 0;
        if (px < x + BUTTON) {
            step = -1;
        } else if (px >= x + STEPPER_W - BUTTON) {
            step = 1;
        }
        int next = Math.clamp(this.findShownScale() + step, 1, this.mostScale);
        if (step != 0 && next != this.findShownScale()) {
            this.scale = next;
            this.onScale.accept(next);
        }
        return true;
    }

    private void paintStepper(Canvas canvas, float a) {
        int x = this.findStepperX();
        int y = this.frame.getY() + 4;
        boolean over = this.isHovered();
        double px = this.getPointerX();
        double py = this.getPointerY();
        boolean onRow = over && py >= y && py < y + BUTTON;
        canvas.fill(x, y, x + STEPPER_W, y + BUTTON, Colors.fade(Palette.PANEL_EDGE, a));
        if (onRow && px >= x && px < x + BUTTON) {
            canvas.fill(x, y, x + BUTTON, y + BUTTON, Colors.fade(0x30B48CFF, a));
        }
        int plusX = x + STEPPER_W - BUTTON;
        if (onRow && px >= plusX && px < plusX + BUTTON) {
            canvas.fill(plusX, y, plusX + BUTTON, y + BUTTON, Colors.fade(0x30B48CFF, a));
        }
        int ink = Colors.fade(Palette.DIM, a);
        canvas.fill(x + 4, y + 7, x + 10, y + 8, ink);
        canvas.fill(plusX + 4, y + 7, plusX + 10, y + 8, ink);
        canvas.fill(plusX + 7, y + 4, plusX + 8, y + 11, ink);
        String label = this.findShownScale() + "x";
        canvas.drawText(label, x + (STEPPER_W - canvas.measureText(label)) / 2, y + 4,
                Colors.fade(this.scale == 0 ? Palette.DIM : Palette.TEXT, a));
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
        DevModView shown = this.findMod();
        boolean overview = this.tab == Tab.OVERVIEW && shown != null;
        this.picker.update(shown, dt);
        boolean picker = overview && this.picker.isOpen();
        boolean naming = picker && this.picker.isNaming();
        boolean merging = picker && this.picker.isMerging();

        boolean backing = overview && !picker && shown != null && !shown.savedBuilds().isEmpty();
        int count = backing ? 4 : 3;
        int buttonsY = top + height - BUTTON_H - PAD;
        int buttonW = (width - (count + 1) * PAD) / count;
        this.reload.setVisible(overview && !picker);
        this.restart.setVisible(overview && !picker);
        this.back.setVisible(backing);
        this.pause.setVisible(overview && !picker && watching);
        this.resume.setVisible(overview && !picker && !watching);
        this.confirm.setVisible(picker && !naming);
        this.merge.setVisible(picker && !naming && !merging);
        this.cancel.setVisible(picker && !naming);
        this.naming.setVisible(naming);
        if (naming) {
            this.naming.setBounds(left + PAD, buttonsY + 2, width - 2 * PAD, BUTTON_H - 4);
        } else if (picker) {
            this.confirm.label(this.picker.findConfirmLabel(shown), this.picker.findConfirmShort());
            this.confirm.setActive(this.picker.isConfirmActive(shown));

            int room = width - (merging ? 3 : 4) * PAD;
            int first = merging ? room * 3 / 5 : room * 2 / 5;
            int rest = merging ? room - first : (room - first) / 2;
            this.confirm.setBounds(left + PAD, buttonsY, first, BUTTON_H);
            this.merge.setBounds(left + 2 * PAD + first, buttonsY, rest, BUTTON_H);
            this.cancel.setBounds(left + (merging ? 2 : 3) * PAD + first + (merging ? 0 : rest), buttonsY,
                    merging ? rest : room - first - rest, BUTTON_H);
        }
        this.reload.setBounds(left + PAD, buttonsY, buttonW, BUTTON_H);
        this.restart.setBounds(left + 2 * PAD + buttonW, buttonsY, buttonW, BUTTON_H);
        this.back.setBounds(left + 3 * PAD + 2 * buttonW, buttonsY, buttonW, BUTTON_H);
        int last = left + count * PAD + (count - 1) * buttonW;
        this.pause.setBounds(last, buttonsY, buttonW, BUTTON_H);
        this.resume.setBounds(last, buttonsY, buttonW, BUTTON_H);
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
        if (this.pressStepper(px, py)) {
            return true;
        }
        DevModView shown = this.findMod();
        if (shown != null && py >= this.findBodyTop() && py < this.findBodyBottom()) {
            int[] mcp = this.mcpRow;
            if (this.tab == Tab.OVERVIEW && !this.picker.isOpen() && mcp != null && py >= mcp[0] && py < mcp[1]) {
                this.getMcp.run();
                return true;
            }
            for (CrashRow row : this.crashRows) {
                if (py >= row.top() && py < row.bottom() && !row.crash().source().isEmpty()) {
                    this.host.openSource(shown.id(), row.crash());
                    return true;
                }
            }
            if (this.tab == Tab.OVERVIEW && this.picker.isOpen() && this.picker.click(px, py)) {
                this.sinceRead = REFRESH_SECONDS;
                return true;
            }
            for (BuildRow row : this.buildRows) {
                if (py >= row.top() && py < row.bottom()) {
                    this.openPicker(row.build());
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

        for (Button button : List.of(this.reload, this.restart, this.back, this.pause, this.resume,
                this.confirm, this.merge, this.cancel)) {
            button.useShortForm(button.measure(canvas, false) > button.getWidth());
        }
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
        boolean hovered = this.isHovered();
        DevCards.paintButtons(canvas, a, y0 + 4, this.findMaximizeX(), this.findCloseX(),
                hovered && this.isOnButton(this.getPointerX(), this.getPointerY(), this.findMaximizeX()),
                hovered && this.isOnButton(this.getPointerX(), this.getPointerY(), this.findCloseX()),
                this.frame.isMaximized());
        this.paintStepper(canvas, a);
        if (mod != null) {
            int pillLeft = DevCards.paintPill(canvas, mod.state(), this.findStepperX() - 6, y0 + 5, a);
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
        this.mcpRow = null;
        this.buildRows.clear();
        int cardX = x0 + PAD;
        int cardW = this.frame.getWidth() - 2 * PAD;
        if (mod == null) {
            y = DevCards.paintText(canvas, Lang.get("hotwire.dev.window.no_mods"), cardX, y, cardW, Palette.DIM, a);
        } else {
            y = switch (this.tab) {
                case OVERVIEW -> this.picker.isOpen() ? this.picker.paint(canvas, mod, cardX, y, cardW, a,
                        this.getPointerX(), this.getPointerY(), this.isHovered() && this.getPointerY() >= bodyTop
                        && this.getPointerY() < bodyBottom)
                        : this.paintOverview(canvas, mod, cardX, y, cardW, a);
                case HISTORY -> this.paintHistory(canvas, mod, cardX, y, cardW, a);
                case CRASHES -> this.paintCrashes(canvas, mod, cardX, y, cardW, a);
                case MIXINS -> DevCards.paintMixins(canvas, mod, cardX, y, cardW, a);
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

    private int paintOverview(Canvas canvas, DevModView mod, int x, int y, int w, float a) {
        String build = mod.runningBuild() == 0 ? Lang.get("hotwire.dev.window.started_with")
                : Lang.get("hotwire.dev.window.build", mod.runningBuild(), DevCards.describeAgo(mod.builtAt()));
        if (mod.events().isEmpty()) {
            y = DevCards.paintCard(canvas, x, y, w, DevCards.findColor(mod.state()), build, "",
                    mod.line().isEmpty() ? List.of()
                    : List.of(mod.line()), Palette.DIM, a);
        } else {
            DevEvent last = mod.events().getFirst();
            List<String> lines = new ArrayList<>(last.lines());
            if (!mod.line().isEmpty()) {
                lines.add(mod.line());
            }
            lines.add(build);
            y = DevCards.paintCard(canvas, x, y, w, DevCards.findColor(last.kind()), last.title(),
                    DevCards.describeAgo(last.at()), lines,
                    Palette.DIM, a);
        }
        if (!mod.crashes().isEmpty()) {
            DevCrash crash = mod.crashes().getFirst();
            y = this.paintCrash(canvas, crash, DevCards.describeAgo(crash.lastAt()), x, y, w, a);
        }
        int runtimeColor = this.fullRuntime ? Palette.GOOD : Palette.OFF_TINT;
        y = DevCards.paintCard(canvas, x, y, w, runtimeColor, Lang.get("hotwire.dev.window.runtime"), "",
                List.of(this.runtime), runtimeColor, a);

        int top = y;
        y = this.mcpInstalled ? DevCards.paintCard(canvas, x, y, w, Palette.ACCENT_SOFT,
                Lang.get("hotwire.dev.mcp.on"), "",
                List.of(Lang.get("hotwire.dev.mcp.on.line")), Palette.DIM, a)
                : DevCards.paintCard(canvas, x, y, w, Palette.ACCENT, Lang.get("hotwire.dev.mcp.get"), "",
                        List.of(Lang.get("hotwire.dev.mcp.get.line")), Palette.DIM, a);
        this.mcpRow = this.mcpInstalled ? null : new int[] {top, y};
        return y;
    }

    public void setMcp(boolean installed, Runnable getIt) {
        this.mcpInstalled = installed;
        this.getMcp = getIt;
    }

    private int paintHistory(Canvas canvas, DevModView mod, int x, int y, int w, float a) {
        if (mod.events().isEmpty()) {
            return DevCards.paintText(canvas, Lang.get("hotwire.dev.window.no_history"), x, y, w, Palette.DIM, a);
        }
        boolean runningTold = false;
        for (DevEvent event : mod.events()) {
            String clock = LocalTime.ofInstant(Instant.ofEpochMilli(event.at()), ZoneId.systemDefault()).format(CLOCK);
            List<String> lines = new ArrayList<>(event.lines());
            boolean saved = event.build() >= 0 && mod.savedBuilds().contains(event.build());
            if (event.build() >= 0 && event.build() == mod.runningBuild() && !runningTold) {
                runningTold = true;
                lines.add(Lang.get("hotwire.dev.build.running", event.build()));
            } else if (saved) {
                lines.add(Lang.get("hotwire.dev.build.back"));
            }
            int bottom = DevCards.paintCard(canvas, x, y, w, DevCards.findColor(event.kind()), event.title(), clock,
                    lines,
                    Palette.DIM, a);
            if (saved && !lines.isEmpty() && lines.getLast().equals(Lang.get("hotwire.dev.build.back"))) {
                this.buildRows.add(new BuildRow(y, bottom, event.build()));
            }
            y = bottom;
        }
        return y;
    }

    private int paintCrashes(Canvas canvas, DevModView mod, int x, int y, int w, float a) {
        if (mod.crashes().isEmpty()) {
            return DevCards.paintText(canvas, Lang.get("hotwire.dev.window.no_crashes"), x, y, w, Palette.DIM, a);
        }
        for (DevCrash crash : mod.crashes()) {
            String when = crash.count() == 1 ? DevCards.describeAgo(crash.lastAt())
                    : "x" + crash.count() + ", " + DevCards.describeAgo(crash.lastAt());
            y = this.paintCrash(canvas, crash, when, x, y, w, a);
        }
        return y;
    }

    private int paintCrash(Canvas canvas, DevCrash crash, String when, int x, int y, int w, float a) {
        List<String> lines = crash.source().isEmpty() ? List.of(crash.where())
                : List.of(crash.where(), Lang.get("hotwire.dev.crash.open"));
        int bottom = DevCards.paintCard(canvas, x, y, w, Palette.BAD, crash.what(), when, lines, Palette.DIM, a);
        this.crashRows.add(new CrashRow(y, bottom, crash));
        return bottom;
    }

}
