package dev.moon.hotwire.ui;

import dev.moon.hotwire.dev.DevHost;
import dev.moon.hotwire.dev.DevModView;
import dev.moon.hotwire.dev.DevState;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

public final class DevWindow extends Element {
    static final int OVERVIEW = 0;
    static final int HISTORY = 1;
    static final int CRASHES = 2;
    static final int MIXINS = 3;
    static final int BUILDS = 4;
    private static final int NO_MODS = 5;
    private static final int TITLE = 24;

    private static final int CARDS_WIDTH = 220;
    private static final int CARDS_REACH = 60;
    private static final int MIN_W = 260;
    private static final int MIN_H = 170;

    private static final int NARROW = 400;
    private static final int RISE = 8;
    private static final int SHADOW = 5;
    private static final int FACE = Palette.PANEL | 0xFF000000;
    private static final float REFRESH_SECONDS = 0.25f;

    private static final int ESCAPE = 256;

    public static final int PEEK_KEY = 342;
    private static final String CLOSE = "dev_close";
    private static final String MAXIMIZE = "dev_maximize";
    private static final String PUT_BACK = "dev_put_back";
    private static final String LESS = "dev_less";
    private static final String MORE = "dev_more";
    private static final String NEXT = "dev_next";
    private static final String SETTINGS = "dev_settings";

    private static final String[] SIGNS = {"dev_overview", "dev_history", "dev_crashes", "dev_mixins", "dev_builds"};

    private final DevHost host;
    private final WindowFrame frame = new WindowFrame(MIN_W, MIN_H, TITLE);
    private final double @Nullable [] saved;
    private final Consumer<double[]> onPlaced;
    private final IntConsumer onScale;
    private final DoubleConsumer onSeeThrough;
    private final Runnable onClose;
    private final EasedValue shown = EasedValue.ofSpeed(14);
    private final Text name = Kit.text("", Palette.ACCENT_SOFT);
    private final Part next = new Knob(() -> NEXT, Palette.ON, this::showNextMod);
    private final Line pill = Kit.row();
    private final Text scaleWord = Kit.dim("");
    private final Line stepper = Kit.row(new Knob(() -> LESS, Palette.ON, () -> this.stepScale(-1)), this.scaleWord,
            new Knob(() -> MORE, Palette.ON, () -> this.stepScale(1))).gap(2);
    private final Tabs rail = Kit.tabs(Tabs.Look.RAIL, this::pickTab);
    private final DevOverview overview;
    private final DevHistory lists;
    private final BuildPicker picker;
    private final Deck deck;
    private final Line tree;
    private List<DevModView> mods = List.of();
    private int modIndex;
    private @Nullable DevState pillState;
    private float sinceRead = REFRESH_SECONDS;

    private int scale;
    private int gameScale = 1;
    private int mostScale = 1;
    private double seeThrough;
    private Runnable openSettings = () -> { };
    private int screenWidth = 854;
    private int placedX;
    private int placedY;

    @SuppressWarnings("this-escape")
    public DevWindow(DevHost host, String runtime, boolean fullRuntime, double @Nullable [] saved,
            Consumer<double[]> onPlaced, int scale, IntConsumer onScale, double seeThrough,
            DoubleConsumer onSeeThrough, Runnable onClose) {
        this.host = host;
        this.saved = saved == null ? null : saved.clone();
        this.onPlaced = onPlaced;
        this.scale = Math.max(0, scale);
        this.onScale = onScale;
        this.seeThrough = seeThrough;
        DevCards.setSeeThrough(seeThrough);
        this.onSeeThrough = onSeeThrough;
        this.onClose = onClose;
        this.picker = new BuildPicker(host, () -> this.rail.press(OVERVIEW));
        this.overview = new DevOverview(host, runtime, fullRuntime, this::openBuilds);
        this.lists = new DevHistory(host, this::openBuilds);
        for (int tab = OVERVIEW; tab <= BUILDS; tab++) {
            String word = List.of("overview", "history", "crashes", "mixins", "builds").get(tab);
            this.rail.tab(Lang.get("hotwire.dev.tab." + word), SIGNS[tab], false);
        }
        this.deck = Kit.deck(this.overview.part, this.lists.history, this.lists.crashes, this.lists.mixins,
                this.picker.part, Kit.column(DevCards.makeLine(Lang.get("hotwire.dev.window.no_mods"), Palette.DIM))
                        .pad(8, 8));
        Line bar = Kit.row(Kit.text(Lang.get("hotwire.dev.window.title")), this.name.grow(1), this.next,
                this.pill.spare(), Kit.dim(Lang.get("hotwire.dev.see_through")).spare(),
                new Slider(() -> this.seeThrough, this::setSeeThrough,
                        () -> this.onSeeThrough.accept(this.seeThrough)),
                this.stepper, new Knob(() -> SETTINGS, Palette.ON, () -> this.openSettings.run()),
                new Knob(() -> this.frame.isMaximized() ? PUT_BACK : MAXIMIZE, Palette.ON, this::toggleMaximized),
                new Knob(() -> CLOSE, Palette.BAD, () -> this.onClose.run())).gap(5).pad(8, 0);
        bar.height(TITLE);
        this.tree = this.add(Kit.column(bar, Kit.rule(), Kit.row(this.rail, this.deck).cross(Line.Cross.STRETCH)
                .grow(1)));
        this.add(new Grip());
        this.shown.snap(1);
    }

    private void openBuilds(int build, int mode) {
        DevModView mod = this.findMod();
        if (mod != null) {
            this.picker.open(mod, build, mode);
            this.showTab(BUILDS);
        }
    }

    private void pickTab(int index) {
        this.picker.reset();
        this.showTab(index);
    }

    private void showTab(int index) {
        this.rail.choose(index);
        this.refresh();
    }

    private void showNextMod() {
        this.modIndex = (this.modIndex + 1) % Math.max(1, this.mods.size());
        this.picker.reset();
        this.refresh();
    }

    private void toggleMaximized() {
        this.frame.toggleMaximized();
        this.onPlaced.accept(this.frame.save());
    }

    private void setSeeThrough(double part) {
        this.seeThrough = part;
        DevCards.setSeeThrough(part);
    }

    public void setPeeking(boolean peeking) {
        DevCards.setSeeThrough(peeking ? 1 : this.seeThrough);
    }

    private int findShownScale() {
        return Math.min(this.scale == 0 ? this.gameScale : this.scale, this.mostScale);
    }

    private void stepScale(int step) {
        int stepped = Math.clamp(this.findShownScale() + step, 1, this.mostScale);
        if (stepped != this.findShownScale()) {
            this.scale = stepped;
            this.onScale.accept(stepped);
        }
    }

    private @Nullable DevModView findMod() {
        return this.mods.isEmpty() ? null : this.mods.get(Math.min(this.modIndex, this.mods.size() - 1));
    }

    private void refresh() {
        this.sinceRead = 0;
        this.mods = this.host.getDevMods();
        DevModView mod = this.findMod();
        this.next.show(this.mods.size() > 1);
        this.scaleWord.set(this.findShownScale() + "x").ink(this.scale == 0 ? Palette.DIM : Palette.TEXT);
        this.rail.setCount(CRASHES, mod == null ? 0 : mod.crashes().size());
        DevState state = mod == null ? null : mod.state();
        if (state != this.pillState) {
            this.pillState = state;
            this.pill.clear();
            if (state != null) {
                int ink = DevCards.findColor(state);
                this.pill.with(new Tag(Lang.get("hotwire.dev.state." + state.name().toLowerCase(Locale.ROOT)), ink,
                        ink));
            }
        }
        if (mod == null) {
            this.name.set("");
            this.deck.turnTo(NO_MODS);
            return;
        }
        this.name.set(this.mods.size() > 1 ? mod.name() + " (" + (this.modIndex + 1) + "/" + this.mods.size() + ")"
                : mod.name());
        this.deck.turnTo(this.rail.getChosen());
        boolean narrow = this.frame.getWidth() < NARROW;
        switch (this.deck.getAt()) {
            case OVERVIEW -> this.overview.show(mod, narrow);
            case HISTORY -> this.lists.showHistory(mod);
            case CRASHES -> this.lists.showCrashes(mod);
            case MIXINS -> this.lists.showMixins(mod);
            default -> this.picker.show(mod, this.deck.getWidth(), narrow);
        }
    }

    public int findCardsTop(int screenWidth) {
        boolean underCards = this.frame.getY() < CARDS_REACH && this.frame.getX() + this.frame.getWidth() > screenWidth
                - CARDS_WIDTH;
        return underCards ? this.frame.getY() + TITLE : 0;
    }

    public int getScale() {
        return this.scale;
    }

    public void setScaleRange(int game, int most) {
        this.gameScale = Math.max(1, game);
        this.mostScale = Math.max(1, most);
    }

    public void setSettings(Runnable open) {
        this.openSettings = open;
    }

    public void setMcp(boolean installed, Runnable getIt) {
        this.overview.setMcp(installed, getIt);
    }

    public int[] getFrameBounds() {
        return this.frame.getBounds();
    }

    public void setScreen(int width, int height) {
        this.screenWidth = width;
        this.frame.setScreen(width, height, this.saved, Math.clamp(width * 2 / 5, Math.min(MIN_W, width), 340),
                Math.clamp(height * 3 / 4, Math.min(MIN_H, height), 280), 8, Math.max(0, Math.min(36, height / 8)));
    }

    public void present() {
        this.shown.snap(0);
        this.shown.to(1);
    }

    public int[] getStepperBounds() {
        return new int[] {this.stepper.getX(), this.stepper.getY(), this.stepper.getWidth(),
            this.stepper.getHeight()};
    }

    public int @Nullable [] findBuildRowForTests(int build) {
        return this.lists.findBuildRow(build);
    }

    public void showTabForTests(int index) {
        this.rail.press(index);
    }

    public boolean pressRollBackForTests() {
        return this.pressOverviewForTests("rollback");
    }

    public boolean pressOverviewForTests(String name) {
        this.refresh();
        return this.deck.getAt() == OVERVIEW && this.overview.press(name);
    }

    public void pressSettingsForTests() {
        this.openSettings.run();
    }

    private boolean isPicking() {
        return this.deck.getAt() == BUILDS;
    }

    public boolean confirmPickForTests() {
        if (this.isPicking()) {
            this.picker.confirm();
        }
        return this.isPicking();
    }

    public void chooseModeForTests(int mode) {
        this.picker.chooseMode(mode);
    }

    public boolean startManageForTests() {
        if (this.isPicking()) {
            this.picker.startManage();
        }
        return this.isPicking();
    }

    public boolean startMergeForTests() {
        if (this.isPicking()) {
            this.picker.startMerge();
        }
        return this.isPicking();
    }

    public void pressBuildForTests(int build) {
        this.picker.pick(build);
    }

    public void pinForTests() {
        if (this.isPicking()) {
            this.picker.pin();
        }
    }

    public void nameForTests(String name) {
        DevModView mod = this.findMod();
        if (mod != null && this.isPicking()) {
            this.host.rename(mod.id(), this.picker.getPicked(), name);
        }
    }

    public void copyForTests() {
        if (this.isPicking()) {
            this.picker.copy();
        }
    }

    public void cancelPickForTests() {
        this.picker.cancel();
    }

    @Override
    protected boolean onKey(int key, int modifiers) {
        if (this.picker.isSettingKey()) {
            this.picker.takeKey(key, ESCAPE);
            return true;
        }
        if (key == PEEK_KEY) {
            this.setPeeking(true);
            return true;
        }
        return false;
    }

    @Override
    public void step(float dt) {
        this.shown.step(dt);
        this.tree.setAlpha(this.shown.getValue());
        this.tree.setLift((1 - this.shown.getValue()) * RISE);
        this.sinceRead += dt;
        if (this.sinceRead >= REFRESH_SECONDS) {
            this.refresh();
        }
        this.frame.step(dt);
        this.setBounds(this.frame.getX(), this.frame.getY(), this.frame.getWidth(), this.frame.getHeight());
        super.step(dt);
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        if (button == 0 && this.frame.press(px, py, System.nanoTime()) && !this.frame.isGrabbed()) {
            this.onPlaced.accept(this.frame.save());
        }
        return true;
    }

    @Override
    protected boolean onDrag(double px, double py, int button) {
        if (this.frame.isGrabbed()) {
            this.frame.drag(px, py);
        }
        return this.frame.isGrabbed();
    }

    @Override
    protected boolean onRelease(double px, double py, int button) {
        if (this.frame.release()) {
            this.onPlaced.accept(this.frame.save());
        }
        return true;
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        float in = alpha * this.shown.getValue();
        int x0 = this.getX();
        int y0 = this.getY();
        int x1 = x0 + this.getWidth();
        int y1 = y0 + this.getHeight();
        this.tree.layIfChanged(canvas, x0, y0, this.getWidth(), this.getHeight());

        if (x0 != this.placedX || y0 != this.placedY) {
            this.placedX = x0;
            this.placedY = y0;
            this.tree.lay(canvas, x0, y0, this.getWidth(), this.getHeight());
        }
        int[] preview = this.frame.findPreview();
        if (preview != null) {
            canvas.fill(preview[0], preview[1], preview[0] + preview[2], preview[1] + preview[3],
                    Colors.fade(Palette.ON, in * 0.12f));
            paintEdge(canvas, preview[0], preview[1], preview[0] + preview[2], preview[1] + preview[3],
                    Colors.fade(Palette.ON, in * 0.6f));
        }
        Canvas.Cursor cursor = this.frame.findCursor(this.getPointerX(), this.getPointerY(), this.isHovered());
        if (cursor != null) {
            canvas.requestCursor(cursor);
        }
        y0 += Math.round((1 - this.shown.getValue()) * RISE);
        y1 += Math.round((1 - this.shown.getValue()) * RISE);
        float solid = in * DevCards.getSolid();

        for (int ring = 1; ring <= SHADOW; ring++) {
            paintEdge(canvas, x0 - ring - 1, y0 - ring, x1 + ring + 1, y1 + ring + 2,
                    Colors.fade(0xFF000000, solid * 0.07f * (SHADOW + 1 - ring)));
        }
        paintEdge(canvas, x0 - 1, y0 - 1, x1 + 1, y1 + 1, Colors.fade(Palette.PANEL_EDGE, in));
        canvas.fill(x0, y0, x1, y1, Colors.fade(FACE, solid));
        canvas.fillAcross(x0, y0, x1, y0 + TITLE, Colors.fade(Palette.ON, in * 0.14f),
                Colors.fade(Palette.ON, in * 0.02f));
    }

    @Override
    protected void paintOver(Canvas canvas, float alpha) {
        float in = alpha * this.shown.getValue();
        int lift = Math.round((1 - this.shown.getValue()) * RISE);
        int x1 = this.getX() + this.getWidth();
        int y1 = this.getY() + this.getHeight() + lift;
        canvas.fillAcross(this.getX(), this.getY() + lift, x1, this.getY() + lift + 2, Colors.fade(Palette.ON, in),
                Colors.fade(Palette.ACCENT, in));
        for (int dot = 0; dot < 3 && !this.frame.isMaximized(); dot++) {
            canvas.fill(x1 - 3 - dot * 3, y1 - 3, x1 - 2 - dot * 3, y1 - 2, Colors.fade(Palette.FAINT, in));
            canvas.fill(x1 - 3, y1 - 3 - dot * 3, x1 - 2, y1 - 2 - dot * 3, Colors.fade(Palette.FAINT, in));
        }
        DevModView mod = this.findMod();
        CompareHud.paintOn(canvas, this.screenWidth, mod == null ? null : mod.compare(), this.host.getCompareKey(),
                this.host.getExtras(), alpha);
    }

    private static void paintEdge(Canvas canvas, int x0, int y0, int x1, int y1, int ink) {
        canvas.fill(x0, y0, x1, y0 + 1, ink);
        canvas.fill(x0, y1 - 1, x1, y1, ink);
        canvas.fill(x0, y0 + 1, x0 + 1, y1 - 1, ink);
        canvas.fill(x1 - 1, y0 + 1, x1, y1 - 1, ink);
    }

    private final class Grip extends Element {
        @Override
        boolean contains(double px, double py) {
            return DevWindow.this.frame.findEdges(px, py) != 0;
        }

        @Override
        protected boolean onClick(double px, double py, int button) {
            return DevWindow.this.onClick(px, py, button);
        }

        @Override
        protected boolean onDrag(double px, double py, int button) {
            return DevWindow.this.onDrag(px, py, button);
        }

        @Override
        protected boolean onRelease(double px, double py, int button) {
            return DevWindow.this.onRelease(px, py, button);
        }
    }

    private static final class Knob extends Part {
        private static final int SIDE = 14;
        private final Supplier<String> sign;
        private final int hot;
        private final Runnable action;
        private final EasedValue hover = EasedValue.ofSpeed(16);

        Knob(Supplier<String> sign, int hot, Runnable action) {
            this.sign = sign;
            this.hot = hot;
            this.action = action;
            this.width(SIDE).height(SIDE);
        }

        @Override
        public void step(float dt) {
            this.hover.to(this.isHovered() ? 1 : 0);
            this.hover.step(dt);
        }

        @Override
        protected void paint(Canvas canvas, float alpha) {
            int x = this.getX();
            int y = this.getY();
            float lit = this.hover.getValue();
            canvas.fill(x, y, x + SIDE, y + SIDE, Colors.fade(this.hot, alpha * lit * 0.3f));
            canvas.drawIcon(this.sign.get(), x + 3, y + 3, 7, Colors.fade(Colors.mix(Palette.DIM, Palette.TEXT, lit),
                    alpha));
        }

        @Override
        protected boolean onClick(double px, double py, int button) {
            if (button == 0) {
                this.action.run();
            }
            return true;
        }
    }
}
