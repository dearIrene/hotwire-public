package dev.moon.hotwire.ui;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

public final class Kit {
    private Kit() {
    }

    public static Line row(Part... parts) {
        return new Line(false, parts);
    }

    public static Line column(Part... parts) {
        return new Line(true, parts);
    }

    public static Text text(String text) {
        return new Text(text, Palette.TEXT);
    }

    public static Text dim(String text) {
        return new Text(text, Palette.DIM);
    }

    public static Text text(String text, int ink) {
        return new Text(text, ink);
    }

    public static Part space() {
        return new Part().grow(1);
    }

    public static Part rule() {
        return new Fill(Palette.PANEL_EDGE).height(1);
    }

    public static Push push(String label, Runnable action) {
        return new Push(label, false, action);
    }

    public static Push lead(String label, Runnable action) {
        return new Push(label, true, action);
    }

    public static Part icon(String icon, int side, int ink) {
        return new Icon(icon, null, "", ink).width(side).height(side);
    }

    public static Part picture(Supplier<Canvas.@Nullable Image> image, String name, int side) {
        return new Icon(null, image, name, Palette.TEXT).width(side).height(side);
    }

    public static Scroll scroll(Part inner) {
        return new Scroll(inner);
    }

    public static Tabs tabs(Tabs.Look look, IntConsumer picked) {
        return new Tabs(look, picked);
    }

    public static Part meter(DoubleSupplier part) {
        return new Meter(part).height(3);
    }

    public static Part check(BooleanSupplier ticked, Runnable action) {
        return new Check(ticked).width(Check.SIDE).height(Check.SIDE).press((button, modifiers) -> action.run());
    }

    static final class Check extends Part {
        static final int SIDE = 10;
        private final BooleanSupplier ticked;
        private final EasedValue on = EasedValue.ofSpeed(18);

        Check(BooleanSupplier ticked) {
            this.ticked = ticked;
        }

        @Override
        public void step(float dt) {
            this.on.to(this.ticked.getAsBoolean() ? 1 : 0);
            this.on.step(dt);
        }

        @Override
        protected void paint(Canvas canvas, float alpha) {
            int x = this.getX();
            int y = this.getY();
            float on = this.on.getValue();
            canvas.fill(x, y, x + SIDE, y + SIDE, Colors.fade(Colors.mix(Palette.PANEL_EDGE, Palette.ON, on), alpha));
            canvas.fill(x + 1, y + 1, x + SIDE - 1, y + SIDE - 1, Colors.fade(Palette.PANEL, alpha));
            canvas.fill(x + 3, y + 3, x + SIDE - 3, y + SIDE - 3, Colors.fade(Palette.ON, alpha * on));
        }
    }

    public static Part cells(DoubleSupplier part) {
        return new Cells(part).height(2 * Cells.SIDE + Cells.GAP);
    }

    public static Part fit(Element... inner) {
        return new Fit(inner);
    }

    public static Drawer drawer(boolean down, Part inner, int size) {
        return new Drawer(down, inner, size);
    }

    public static Deck deck(Part... pages) {
        return new Deck(pages);
    }

    static final class Fill extends Part {
        private final int argb;

        Fill(int argb) {
            this.argb = argb;
        }

        @Override
        protected void paint(Canvas canvas, float alpha) {
            canvas.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(),
                    Colors.fade(this.argb, alpha));
        }
    }

    static final class Meter extends Part {
        private final DoubleSupplier part;
        private final EasedValue shown = EasedValue.ofSpeed(9);

        Meter(DoubleSupplier part) {
            this.part = part;
        }

        @Override
        public void step(float dt) {
            this.shown.to((float) Math.clamp(this.part.getAsDouble(), 0, 1));
            this.shown.step(dt);
        }

        @Override
        protected void paint(Canvas canvas, float alpha) {
            int x = this.getX();
            int y = this.getY();
            int bottom = y + this.getHeight();
            canvas.fill(x, y, x + this.getWidth(), bottom, Colors.fade(Palette.TRACK, alpha));
            int filled = Math.round(this.getWidth() * this.shown.getValue());
            if (filled > 0) {
                canvas.fillAcross(x, y, x + filled, bottom, Colors.fade(Palette.ON, alpha),
                        Colors.fade(Palette.ACCENT, alpha));
            }
        }
    }

    static final class Cells extends Part {
        static final int SIDE = 12;
        static final int GAP = 3;

        private static final int GLARE = 18;
        private static final float GLARE_SPEED = 150;
        private static final int GLARE_REST = 360;
        private static final float GLARE_MOST = 0.16f;
        private float clock;
        private final DoubleSupplier part;
        private final EasedValue shown = EasedValue.ofSpeed(6);

        Cells(DoubleSupplier part) {
            this.part = part;
        }

        @Override
        public void step(float dt) {
            this.shown.to((float) Math.clamp(this.part.getAsDouble(), 0, 1));
            this.shown.step(dt);
            this.clock += dt;
        }

        @Override
        protected void paint(Canvas canvas, float alpha) {
            int columns = (this.getWidth() + GAP) / (SIDE + GAP);
            float lit = columns * 2 * this.shown.getValue();

            int reach = this.getWidth() + this.getHeight() + 2 * GLARE;
            int glare = Math.round(this.clock * GLARE_SPEED % (reach + GLARE_REST)) - GLARE;
            for (int cell = 0; cell < columns * 2; cell++) {
                int x = this.getX() + cell / 2 * (SIDE + GAP);
                int y = this.getY() + cell % 2 * (SIDE + GAP);
                float on = Math.clamp(lit - cell, 0, 1);
                int ink = Colors.mix(Palette.TRACK, Colors.mix(Palette.ON, Palette.ACCENT, cell / (columns * 2f)), on);
                canvas.fill(x, y, x + SIDE, y + SIDE, Colors.fade(ink, alpha));
                int far = x - this.getX() + y - this.getY() + SIDE - glare;
                if (on > 0.5f && Math.abs(far) < GLARE + SIDE) {
                    this.paintGlare(canvas, x, y, this.getX() + glare - (y - this.getY()), alpha);
                }
            }
        }

        private void paintGlare(Canvas canvas, int x, int y, int middle, float alpha) {
            int clear = Palette.TEXT & 0x00FFFFFF;
            int most = Colors.fade(Palette.TEXT, alpha * GLARE_MOST);
            canvas.pushClip(x, y, x + SIDE, y + SIDE);
            for (int row = 0; row < SIDE; row++) {
                canvas.fillAcross(middle - row - GLARE, y + row, middle - row, y + row + 1, clear, most);
                canvas.fillAcross(middle - row, y + row, middle - row + GLARE, y + row + 1, most, clear);
            }
            canvas.popClip();
        }
    }

    static final class Fit extends Part {
        private final Element[] inner;

        Fit(Element... inner) {
            this.inner = inner;
            for (Element one : inner) {
                this.add(one);
            }
        }

        @Override
        protected void place(Canvas canvas) {
            for (Element one : this.inner) {
                one.setBounds(this.getX(), this.getY(), this.getWidth(), this.getHeight());
            }
        }
    }

    static final class Icon extends Part {
        private static final int[] TILES = {0xFF2B2447, 0xFF2E3A4F, 0xFF4A2F3F, 0xFF3A2F4F, 0xFF2B4549, 0xFF45352B};
        private final @Nullable String pixels;
        private final @Nullable Supplier<Canvas.@Nullable Image> image;
        private final String letter;
        private final int tile;
        private final int ink;

        private Canvas.@Nullable Image drawn;

        Icon(@Nullable String pixels, @Nullable Supplier<Canvas.@Nullable Image> image, String name, int ink) {
            this.pixels = pixels;
            this.image = image;
            this.letter = name.isEmpty() ? "" : name.substring(0, 1).toUpperCase(Locale.ROOT);
            this.tile = TILES[Math.floorMod(name.hashCode(), TILES.length)];
            this.ink = ink;
        }

        @Override
        protected void paint(Canvas canvas, float alpha) {
            int x = this.getX();
            int y = this.getY();
            int side = this.getWidth();
            if (this.pixels != null) {
                canvas.drawIcon(this.pixels, x, y, side, Colors.fade(this.ink, alpha));
                return;
            }
            Canvas.Image found = this.image == null ? null : this.image.get();
            if (found != null && canvas.drawImage(found, x, y, side, side, alpha)) {
                this.drawn = found;
                return;
            }
            if (this.drawn != null && canvas.drawImage(this.drawn, x, y, side, side, alpha)) {
                return;
            }
            canvas.fill(x, y, x + side, y + side, Colors.fade(this.tile, alpha));
            canvas.pushScale(side >= 24 ? 2 : 1, x + side / 2f, y + side / 2f);
            canvas.drawText(this.letter, x + (side - canvas.measureText(this.letter) + 1) / 2, y + side / 2 - 3,
                    Colors.fade(this.ink, alpha));
            canvas.popScale();
        }

        @Override
        protected boolean takesPointer() {
            return false;
        }
    }
}
