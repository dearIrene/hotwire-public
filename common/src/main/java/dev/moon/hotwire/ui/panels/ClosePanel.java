package dev.moon.hotwire.ui.panels;

import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.ui.pages.PendingPage;
import dev.moon.hotwire.ui.widgets.Kit;
import dev.moon.hotwire.ui.widgets.Line;
import dev.moon.hotwire.ui.widgets.Palette;
import dev.moon.hotwire.ui.widgets.Sheet;
import dev.moon.hotwire.ui.widgets.TextField;
import dev.moon.hotwire.update.PendingChange;
import java.util.List;

public final class ClosePanel extends Sheet {
    private static final int LIST_TALL = 110;

    private final Runnable onClose;

    public ClosePanel(Runnable onClose) {
        super(420, null);
        this.onClose = onClose;
        this.setMark(ModScreen.Icons.PENDING);
    }

    public void open(List<PendingChange> waiting) {
        this.title.set(Lang.get("hotwire.close.title"));
        Line list = Kit.column().gap(6);
        for (PendingChange change : waiting) {
            list.with(Kit.column(Kit.text(change.name()), Kit.dim(PendingPage.describeAtClose(change)).wrap()).gap(2));
        }
        this.body.clear().with(Kit.column(Kit.scroll(list).capAt(LIST_TALL)).pad(8, 6).face(Palette.HAIRLINE),
                Kit.text(Lang.get("hotwire.close.plain"), Palette.WARN).wrap());
        this.buttons.clear().with(Kit.space(), this.setLead(Kit.lead(Lang.get("hotwire.close.confirm"), () -> {
            this.dismiss();
            this.onClose.run();
        })), Kit.push(Lang.get("hotwire.close.not_now"), this::dismiss));
        this.show();
    }

    @Override
    public boolean onKey(int key, int modifiers) {
        if (key == TextField.ESCAPE) {
            this.dismiss();
        }
        return true;
    }
}
