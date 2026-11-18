package dev.moon.hotwire.ui.panels;

import dev.moon.hotwire.seam.SharedChange;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.widgets.Kit;
import dev.moon.hotwire.ui.widgets.Line;
import dev.moon.hotwire.ui.widgets.Palette;
import dev.moon.hotwire.ui.widgets.Sheet;
import java.util.List;
import java.util.function.Function;

public final class HostPanel extends Sheet {
    private static final int LIST_TALL = 110;

    private final Function<String, String> names;

    public HostPanel(Runnable onMatch, Runnable onLater, Function<String, String> names) {
        super(400, onLater);
        this.names = names;
        this.title.set(Lang.get("hotwire.host.title"));
        this.buttons.with(Kit.space(), this.setLead(Kit.lead(Lang.get("hotwire.host.match"), onMatch)),
                Kit.push(Lang.get("hotwire.host.later"), onLater));
    }

    public void open(List<SharedChange> changes) {
        Line rows = Kit.column().gap(4);
        for (SharedChange change : changes) {
            rows.with(Kit.text(switch (change.kind()) {
                case ADD -> Lang.get("hotwire.host.added", change.mod());
                case ON -> Lang.get("hotwire.host.on", this.names.apply(change.mod()));
                case OFF -> Lang.get("hotwire.host.off", this.names.apply(change.mod()));
            }));
        }
        this.body.clear().with(Kit.dim(Lang.get("hotwire.host.line")).wrap(),
                Kit.column(Kit.scroll(rows).capAt(LIST_TALL)).pad(8, 6).face(Palette.HAIRLINE));
        this.show();
    }
}
