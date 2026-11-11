package dev.moon.hotwire.ui;

public final class Tag extends Part {
    private static final int SIGN = 5;
    public static final int CLIENT = 0xFF6FC3FF;
    public static final int SERVER = 0xFF7EE8A5;
    public static final String SCREEN = "client";
    public static final String STACK = "server";
    public static final String BOOK = "library";

    private final String word;
    private final int from;
    private final int to;
    private final String[] signs;

    private final String[] letters;
    private int[] starts = {};
    private int[] inks = {};
    private float glow;

    public Tag(String word, int from, int to, String... signs) {
        this.word = word;
        this.from = from;
        this.to = to;
        this.signs = signs;
        this.letters = from == to ? new String[0] : word.split("");
        this.height(Text.HEIGHT + 4);
    }

    public static Tag ofSide(String side) {
        return switch (side) {
            case "client" -> new Tag(Lang.get("hotwire.row.client"), CLIENT, CLIENT, SCREEN);
            case "server" -> new Tag(Lang.get("hotwire.row.server"), SERVER, SERVER, STACK);
            default -> new Tag(Lang.get("hotwire.row.both"), CLIENT, SERVER, SCREEN, STACK);
        };
    }

    public static Tag ofLibrary() {
        return new Tag(Lang.get("hotwire.row.library"), Palette.PATH, Palette.PATH, BOOK);
    }

    public void setGlow(float glow) {
        this.glow = glow;
    }

    private int findLead() {
        return 4 + this.signs.length * (SIGN + 2) + (this.signs.length > 0 ? 1 : 0);
    }

    @Override
    protected void measure(Canvas canvas, int room) {
        int lead = this.findLead();
        this.hugWidth = lead + canvas.measureText(this.word) + 3;
        this.starts = new int[this.letters.length];
        this.inks = new int[this.letters.length];
        int at = 0;
        for (int i = 0; i < this.letters.length; i++) {
            int width = canvas.measureText(this.letters[i]);
            this.starts[i] = at;
            this.inks[i] = Colors.mix(this.from, this.to, (lead + at + width / 2f) / this.hugWidth);
            at += width;
        }
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        int x = this.getX();
        int y = this.getY();
        int right = x + this.getWidth();
        int bottom = y + this.getHeight();
        float edge = alpha * (0.3f + 0.4f * this.glow);
        int left = Colors.fade(this.from, edge);
        int far = Colors.fade(this.to, edge);
        canvas.fillAcross(x, y, right, y + 1, left, far);
        canvas.fillAcross(x, bottom - 1, right, bottom, left, far);
        canvas.fill(x, y + 1, x + 1, bottom - 1, left);
        canvas.fill(right - 1, y + 1, right, bottom - 1, far);
        float ink = alpha * 0.9f;
        for (int sign = 0; sign < this.signs.length; sign++) {
            int signX = 4 + sign * (SIGN + 2);
            canvas.drawIcon(this.signs[sign], x + signX, y + 3, SIGN, Colors.fade(Colors.mix(this.from, this.to,
                    (signX + SIGN / 2f) / this.getWidth()), ink));
        }
        int textX = x + this.findLead();
        if (this.letters.length == 0) {
            canvas.drawText(this.word, textX, y + 2, Colors.fade(this.from, ink));
        }
        for (int i = 0; i < this.letters.length; i++) {
            canvas.drawText(this.letters[i], textX + this.starts[i], y + 2, Colors.fade(this.inks[i], ink));
        }
    }

    @Override
    protected boolean takesPointer() {
        return false;
    }
}
