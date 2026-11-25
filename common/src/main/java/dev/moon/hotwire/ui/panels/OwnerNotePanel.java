package dev.moon.hotwire.ui.panels;

import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.widgets.Kit;
import dev.moon.hotwire.ui.widgets.Palette;
import dev.moon.hotwire.ui.widgets.Sheet;

public final class OwnerNotePanel extends Sheet {
    private final Runnable onRead;

    @SuppressWarnings("this-escape")
    public OwnerNotePanel(Runnable onRead) {
        super(400, null);
        this.onRead = onRead;
        this.title.set(Lang.get("hotwire.owner_note.title"));
        this.buttons.with(Kit.space(), this.setLead(Kit.lead(Lang.get("hotwire.owner_note.read"), this::finish)));
    }

    public void present() {
        this.body.clear().gap(8).with(Kit.text(Lang.get("hotwire.owner_note.hello"), Palette.TEXT).wrap(),
                Kit.text(Lang.get("hotwire.owner_note.what"), Palette.TEXT).wrap(),
                Kit.text(Lang.get("hotwire.owner_note.ask"), Palette.ACCENT).wrap(),
                Kit.text(Lang.get("hotwire.owner_note.origin"), Palette.DIM).wrap());
        this.show();
    }

    private void finish() {
        this.onRead.run();
        this.dismiss();
    }
}
