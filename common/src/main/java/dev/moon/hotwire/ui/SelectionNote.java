package dev.moon.hotwire.ui;

final class SelectionNote extends Element {
    private int y = 60;

    private float shown;
    private final EasedValue chosenTick = EasedValue.ofSpeed(9);
    private final EasedValue neededTick = EasedValue.ofSpeed(9);
    private float noteX;
    private float measured;
    private int chosen;
    private int needed;
    private String text = "";

    void setCounts(int selected, int neededByThem) {
        this.chosen = selected - neededByThem;
        this.needed = neededByThem;
    }

    @Override
    public void step(float dt) {
        this.chosenTick.to(this.chosen);
        this.chosenTick.step(dt);
        this.neededTick.to(this.needed);
        this.neededTick.step(dt);
        int k = this.chosenTick.getRounded();
        int a = this.neededTick.getRounded();
        String now = getTextFor(k, a);
        if (!now.isEmpty()) {
            this.text = now;
        }
        boolean wanted = !now.isEmpty();
        this.shown = EasedValue.lerp(this.shown, wanted ? 1 : 0, EasedValue.computeFactor(wanted ? 16 : 18, dt));
        if (this.shown < 0.02f && !wanted) {
            this.text = "";
            this.noteX = 0;
            this.measured = 0;
        }
        this.noteX = this.noteX < this.measured ? this.measured
                : EasedValue.lerp(this.noteX, this.measured, EasedValue.computeFactor(15, dt));
        super.step(dt);
    }

    private static String getTextFor(int chosen, int needed) {
        if (chosen + needed == 0) {
            return "";
        }
        return needed == 0 ? chosen + " selected" : chosen + " selected  +" + needed + " that need them";
    }

    String getText() {
        return this.text;
    }

    void setY(int y) {
        this.y = y;
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        if (this.text.isEmpty()) {
            return;
        }
        float width = canvas.measureText(this.text);
        this.measured = width;
        float t = this.shown;
        float curve = 1 - (1 - t) * (1 - t) * (1 - t);
        int x = this.getWidth() - 24 - Math.round(Math.max(this.noteX, width));
        canvas.drawText(this.text, x, this.y, Colors.fade(Palette.ACCENT, alpha * curve));
    }
}
