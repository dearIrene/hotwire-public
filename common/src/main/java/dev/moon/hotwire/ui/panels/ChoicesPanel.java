package dev.moon.hotwire.ui.panels;

import dev.moon.hotwire.config.SettingOption;
import dev.moon.hotwire.config.SettingsSection;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.widgets.Canvas;
import dev.moon.hotwire.ui.widgets.Colors;
import dev.moon.hotwire.ui.widgets.Kit;
import dev.moon.hotwire.ui.widgets.Line;
import dev.moon.hotwire.ui.widgets.Palette;
import dev.moon.hotwire.ui.widgets.Part;
import dev.moon.hotwire.ui.widgets.Sheet;
import dev.moon.hotwire.ui.widgets.Tabs;
import dev.moon.hotwire.ui.widgets.Text;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

public final class ChoicesPanel extends Sheet {
    public static final String OFF_LASTS = "off-lasts";
    public static final String ANTI_CRASH = "anti-crash";

    private static final int ON = 1;

    private final Supplier<List<SettingsSection>> sections;
    private final BiConsumer<String, Integer> choose;
    private final Runnable onDone;
    private int lasts;
    private int crash = ON;

    private float sceneClock;

    @SuppressWarnings("this-escape")
    public ChoicesPanel(Supplier<List<SettingsSection>> sections, BiConsumer<String, Integer> choose,
            Runnable onDone) {
        super(400, null);
        this.sections = sections;
        this.choose = choose;
        this.onDone = onDone;
        this.title.set(Lang.get("hotwire.choices.title"));
        this.buttons.with(Kit.dim(Lang.get("hotwire.choices.later")), Kit.space(),
                this.setLead(Kit.lead(Lang.get("hotwire.choices.done"), this::finish)));
    }

    private @Nullable SettingOption find(String id) {
        for (SettingsSection section : this.sections.get()) {
            for (SettingOption option : section.options()) {
                if (option.id().equals(id)) {
                    return option;
                }
            }
        }
        return null;
    }

    public boolean present() {
        SettingOption lasts = this.find(OFF_LASTS);
        SettingOption crash = this.find(ANTI_CRASH);
        if (lasts == null || crash == null) {
            return false;
        }

        this.lasts = lasts.chosen();
        this.crash = ON;
        this.sceneClock = 0;
        Text lastsNote = Kit.dim(lasts.choices().get(this.lasts).explains()).wrap();
        Text crashNote = Kit.text(this.describeCrash(), Palette.TEXT).wrap();
        Tabs[] bars = new Tabs[2];
        Tabs lastsBar = Kit.tabs(Tabs.Look.BOX, choice -> {
            this.lasts = choice;
            bars[0].choose(choice);
            lastsNote.set(lasts.choices().get(choice).explains());
        });
        bars[0] = lastsBar;
        lasts.choices().forEach(choice -> lastsBar.tab(choice.label()));
        lastsBar.choose(this.lasts);
        Tabs crashBar = Kit.tabs(Tabs.Look.BOX, choice -> {
            this.crash = choice;
            this.sceneClock = 0;
            bars[1].choose(choice);
            crashNote.set(this.describeCrash());
        });
        bars[1] = crashBar;
        crash.choices().forEach(choice -> crashBar.tab(choice.label()));
        crashBar.choose(this.crash);

        this.body.clear().gap(4).with(
                Kit.column(Kit.text(Lang.get("hotwire.choices.lasts")).wrap(), lastsBar, lastsNote).gap(3)
                        .cross(Line.Cross.START),
                Kit.row(Kit.column(Kit.text(crash.label(), Palette.ACCENT),
                        Kit.dim(Lang.get("hotwire.choices.anti_crash.now")).wrap()).gap(3).grow(1), new Scene())
                        .gap(10).cross(Line.Cross.END),
                Kit.column(crashBar, crashNote).gap(3).cross(Line.Cross.START));
        this.show();
        return true;
    }

    private String describeCrash() {
        return Lang.get(this.crash == ON ? "hotwire.choices.anti_crash.on" : "hotwire.choices.anti_crash.off");
    }

    private void finish() {
        this.choose.accept(OFF_LASTS, this.lasts);
        this.choose.accept(ANTI_CRASH, this.crash);
        this.onDone.run();
        this.dismiss();
    }

    private final class Scene extends Part {
        private static final int MODS = 5;
        private static final int SIDE = 12;
        private static final int GAP = 8;
        private static final int CRASHING = 2;
        private static final int ROOM = 38;
        private static final float CRASH_AT = 1.3f;
        private static final float ANSWER_AT = 2.1f;
        private static final float LOOP = 4.6f;
        private static final float BURST = 0.7f;
        private static final int OFF_INK = 0xFF4A4460;

        Scene() {
            this.width(MODS * (SIDE + GAP) + GAP).height(ROOM);
        }

        @Override
        public void step(float dt) {
            ChoicesPanel.this.sceneClock = (ChoicesPanel.this.sceneClock + dt) % LOOP;
        }

        private void fill(Canvas canvas, float x, float y, float wide, float tall, int ink, float alpha) {
            if (alpha <= 0.01f) {
                return;
            }
            int left = (int) Math.floor(x);
            int top = (int) Math.floor(y);
            canvas.pushShift(x - left, y - top);
            canvas.fill(left, top, left + Math.round(wide), top + Math.round(tall), Colors.fade(ink, alpha));
            canvas.popShift();
        }

        private void paintMod(Canvas canvas, float x, float y, float wide, float tall, int ink, boolean shut,
                float alpha) {
            this.fill(canvas, x, y, wide, tall, ink, alpha);
            int eye = Palette.BACKDROP | 0xFF000000;
            float eyes = y + tall / 3;
            this.fill(canvas, x + wide * 0.25f, eyes, 2, shut ? 1 : 3, eye, alpha);
            this.fill(canvas, x + wide * 0.75f - 2, eyes, 2, shut ? 1 : 3, eye, alpha);
        }

        private void paintBurst(Canvas canvas, float x, float y, float part, int ink, float alpha) {
            for (int at = 0; at < 4; at++) {
                int far = 8 + 4 * (at >> 1);
                int up = 12 + 7 * (at >> 1);
                this.fill(canvas, x + ((at & 1) == 0 ? -1 : 1) * far * part - 2,
                        y - up * part + 34 * part * part - 2, 4, 4, ink, alpha * (1 - part));
            }
        }

        @Override
        protected void paint(Canvas canvas, float alpha) {
            boolean guarded = ChoicesPanel.this.crash == ON;
            float clock = ChoicesPanel.this.sceneClock;
            float since = clock - ANSWER_AT;
            int floor = this.getY() + this.getHeight() - SIDE;
            for (int mod = 0; mod < MODS; mod++) {
                float x = this.getX() + GAP / 2f + mod * (SIDE + GAP);
                boolean crasher = mod == CRASHING;
                float gone = since - Math.abs(mod - CRASHING) * 0.12f;
                if (!guarded && gone >= 0) {
                    this.paintBurst(canvas, x + SIDE / 2f, floor + SIDE / 2f, Math.min(1, gone / BURST),
                            crasher ? Palette.OFF_TINT : Palette.ON, alpha);
                } else if (crasher && clock >= CRASH_AT && since < 0) {
                    this.paintMod(canvas, x + (float) Math.sin(clock * 60) * 1.5f, floor, SIDE, SIDE,
                            Palette.OFF_TINT, false, alpha);
                    float mark = floor - 11 - (float) Math.abs(Math.sin((clock - CRASH_AT) * 9)) * 4;
                    this.fill(canvas, x + SIDE / 2f - 1, mark, 2, 5, Palette.WARN, alpha);
                    this.fill(canvas, x + SIDE / 2f - 1, mark + 6, 2, 2, Palette.WARN, alpha);
                } else if (crasher && since >= 0) {
                    float jump = since < 0.45f ? (float) Math.sin(since / 0.45f * Math.PI) * 7 : 0;
                    float flat = Math.clamp((since - 0.45f) / 0.2f, 0, 1);
                    float wide = SIDE + 3 * flat;
                    float tall = SIDE - 5 * flat;
                    this.paintMod(canvas, x - (wide - SIDE) / 2, floor + SIDE - tall - jump, wide, tall, OFF_INK,
                            true, alpha);
                } else {
                    float hop = (float) Math.max(0, Math.sin(clock * 6 - mod * 0.9f)) * 6;

                    float cheer = guarded && since >= 0.3f && since < 1.1f
                            ? (float) Math.sin((since - 0.3f) / 0.8f * Math.PI) * 5 : 0;
                    this.paintMod(canvas, x, floor - hop - cheer, SIDE, SIDE, Palette.ON, false, alpha);
                }
            }
            if (guarded && since >= 0 && since < BURST) {
                float middle = this.getX() + GAP / 2f + CRASHING * (SIDE + GAP) + SIDE / 2f;
                float reach = SIDE / 2f + since * 26;
                float ring = alpha * (1 - since / BURST);
                this.fill(canvas, middle - reach, floor + SIDE - 1, reach * 2, 1, Palette.ACCENT, ring);
                this.fill(canvas, middle - reach, floor - 2, 1, SIDE + 2, Palette.ACCENT, ring);
                this.fill(canvas, middle + reach - 1, floor - 2, 1, SIDE + 2, Palette.ACCENT, ring);
            }
        }

        @Override
        protected boolean takesPointer() {
            return false;
        }
    }
}
