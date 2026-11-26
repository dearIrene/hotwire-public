package dev.moon.hotwire.ui.panels;

import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.widgets.Kit;
import dev.moon.hotwire.ui.widgets.Line;
import dev.moon.hotwire.ui.widgets.Palette;
import dev.moon.hotwire.ui.widgets.Push;
import dev.moon.hotwire.ui.widgets.Scroll;
import dev.moon.hotwire.ui.widgets.Sheet;

public final class OwnerNotePanel extends Sheet {
    public static final float WAIT = 5;

    private static final int AROUND = 112;

    private final Runnable onRead;

    private final Line lines = Kit.column().gap(8);
    private final Scroll scroll = Kit.scroll(this.lines);
    private final Push read;
    private float waited;

    private long inSince;

    @SuppressWarnings("this-escape")
    public OwnerNotePanel(Runnable onRead) {
        super(400, null);
        this.onRead = onRead;
        this.title.set(Lang.get("hotwire.owner_note.title"));
        this.read = this.setLead(Kit.lead(this.getLabel(), this::finish));
        this.buttons.with(Kit.space(), this.read);
        this.body.with(this.scroll);
    }

    public void present() {
        this.waited = 0;
        this.inSince = 0;
        this.scroll.reset();
        this.lines.clear().with(Kit.text(Lang.get("hotwire.owner_note.hello"), Palette.TEXT).wrap(),
                Kit.text(Lang.get("hotwire.owner_note.what"), Palette.TEXT).wrap(),
                Kit.text(Lang.get("hotwire.owner_note.ask"), Palette.ACCENT).wrap(),
                Kit.text(Lang.get("hotwire.owner_note.signed"), Palette.ACCENT).wrap(),
                Kit.text(Lang.get("hotwire.owner_note.origin"), Palette.DIM).wrap());
        this.show();
    }

    private void finish() {
        if (this.waited >= WAIT) {
            this.onRead.run();
            this.dismiss();
        }
    }

    String getLabel() {
        int left = (int) Math.ceil(WAIT - this.waited);
        String words = Lang.get("hotwire.owner_note.read");
        return left > 0 ? words + " (" + left + ")" : words;
    }

    @Override
    public void step(float dt) {
        if (this.open() && this.getShown() > 0.9f) {
            long now = System.nanoTime();
            if (this.inSince == 0) {
                this.inSince = now;
            }
            this.waited = Math.min(WAIT, Math.max(this.waited + dt, (now - this.inSince) / 1e9f));
        }
        this.read.label(this.getLabel()).active(this.waited >= WAIT);
        this.scroll.capAt(Math.max(40, this.getHeight() - AROUND));
        super.step(dt);
    }
}
