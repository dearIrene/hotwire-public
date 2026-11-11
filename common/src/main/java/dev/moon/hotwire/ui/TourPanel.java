package dev.moon.hotwire.ui;

import java.util.List;

public final class TourPanel extends Sheet {
    private static final float BEAT = 1.1f;
    private static final List<String> TITLES = List.of("hotwire.tour.switch.title",
            "hotwire.tour.profiles.title", "hotwire.tour.more.title");

    private final Runnable onSeen;
    private final Deck deck;
    private final Push back;
    private final Push next;
    private final Dots dots = new Dots();
    private float clock;
    private int at;

    @SuppressWarnings("this-escape")
    public TourPanel(Runnable onSeen) {
        super(300, null);
        this.onSeen = onSeen;
        this.deck = Kit.deck(makeCard("hotwire.tour.switch.text", this.makeMod("Sodium", 0), this.makeMod("Iris", 1),
                this.makeMod("Zoomify", 2)),
                makeCard("hotwire.tour.profiles.text", this.makeProfile("hotwire.tour.profiles.one", 0),
                        this.makeProfile("hotwire.tour.profiles.two", 1)),
                makeCard("hotwire.tour.more.text", makePlace("store", "hotwire.tour.more.store"),
                        makePlace("updates", "hotwire.tour.more.updates"),
                        makePlace("settings", "hotwire.tour.more.settings")));
        this.body.with(this.deck);
        this.back = Kit.push("", this::turnBack);
        this.next = this.setLead(Kit.lead("", this::turnForward));
        this.buttons.with(this.dots, Kit.space(), this.back, this.next).cross(Line.Cross.CENTER);
        this.setMark("mods");
    }

    void present() {
        this.turnTo(0);
        this.show();
    }

    private static Part makeCard(String text, Part... rows) {
        return Kit.column(Kit.column(rows).gap(5).pad(8, 6),
                Kit.dim(Lang.get(text)).wrap()).gap(8);
    }

    private Part makeMod(String name, int row) {
        return Kit.row(Kit.icon("mods", 10, Palette.DIM), Kit.text(name).grow(1),
                Kit.check(() -> (int) (this.clock / BEAT + 3 - row) % 4 != 0, () -> { })).gap(6)
                .cross(Line.Cross.CENTER);
    }

    private Part makeProfile(String key, int row) {
        return Kit.row(Kit.check(() -> (int) (this.clock / (2 * BEAT)) % 2 == row, () -> { }),
                Kit.text(Lang.get(key)).grow(1)).gap(6).cross(Line.Cross.CENTER);
    }

    private static Part makePlace(String icon, String key) {
        return Kit.row(Kit.icon(icon, 10, Palette.ACCENT_SOFT), Kit.text(Lang.get(key)).grow(1)).gap(6)
                .cross(Line.Cross.CENTER);
    }

    private void turnTo(int card) {
        this.at = card;
        this.deck.turnTo(card);
        this.title.set(Lang.get(TITLES.get(card)));
        boolean last = card == TITLES.size() - 1;
        this.back.label(Lang.get(card == 0 ? "hotwire.tour.skip" : "hotwire.tour.back"));
        this.next.label(Lang.get(last ? "hotwire.tour.done" : "hotwire.tour.next"));
    }

    private void turnBack() {
        if (this.at == 0) {
            this.end();
        } else {
            this.turnTo(this.at - 1);
        }
    }

    private void turnForward() {
        if (this.at == TITLES.size() - 1) {
            this.end();
        } else {
            this.turnTo(this.at + 1);
        }
    }

    private void end() {
        this.onSeen.run();
        this.dismiss();
    }

    @Override
    public void step(float dt) {
        this.clock += dt;
        super.step(dt);
    }

    private final class Dots extends Part {
        private static final int SIDE = 4;
        private static final int STEP = 8;
        private final EasedValue markAt = EasedValue.ofSpeed(16);

        Dots() {
            this.width(TITLES.size() * STEP).height(SIDE);
        }

        @Override
        public void step(float dt) {
            this.markAt.to(TourPanel.this.at * STEP);
            this.markAt.step(dt);
        }

        @Override
        protected void paint(Canvas canvas, float alpha) {
            int y = this.getY();
            for (int card = 0; card < TITLES.size(); card++) {
                int x = this.getX() + card * STEP;
                canvas.fill(x, y, x + SIDE, y + SIDE, Colors.fade(Palette.PANEL_EDGE, alpha));
            }
            int x = this.getX() + this.markAt.getRounded();
            canvas.fill(x, y, x + SIDE, y + SIDE, Colors.fade(Palette.ACCENT, alpha));
        }
    }
}
