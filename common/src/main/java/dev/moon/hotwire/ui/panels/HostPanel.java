package dev.moon.hotwire.ui.panels;

import dev.moon.hotwire.seam.SharedChange;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.widgets.Kit;
import dev.moon.hotwire.ui.widgets.Line;
import dev.moon.hotwire.ui.widgets.Palette;
import dev.moon.hotwire.ui.widgets.Sheet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public final class HostPanel extends Sheet {
    private static final int LIST_TALL = 110;

    private final Function<String, String> names;
    private final Consumer<List<SharedChange>> onMatch;
    private final Runnable onLater;

    private List<SharedChange> shown = List.of();

    private @Nullable Runnable no;

    @SuppressWarnings("this-escape")
    public HostPanel(Consumer<List<SharedChange>> onMatch, Runnable onLater, Function<String, String> names) {
        super(400, onLater);
        this.names = names;
        this.onMatch = onMatch;
        this.onLater = onLater;
    }

    public void open(List<SharedChange> changes) {
        this.no = null;
        this.shown = List.copyOf(changes);
        this.title.set(Lang.get("hotwire.host.title"));
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
        List<SharedChange> asked = this.shown;
        this.buttons.clear().with(Kit.space(), this.setLead(Kit.lead(Lang.get("hotwire.host.match"),
                () -> this.onMatch.accept(asked))), Kit.push(Lang.get("hotwire.host.later"), this.onLater));
        this.show();
    }

    public void confirm(List<String> lines, Runnable yes, Runnable no) {
        this.no = no;
        this.title.set(Lang.get("hotwire.host.check_title"));
        Line rows = Kit.column().gap(4);
        lines.forEach(line -> rows.with(Kit.text(line).wrap()));
        this.body.clear().with(Kit.dim(Lang.get("hotwire.host.check_line")).wrap(),
                Kit.column(Kit.scroll(rows).capAt(LIST_TALL)).pad(8, 6).face(Palette.HAIRLINE));
        this.buttons.clear().with(Kit.space(), this.setLead(Kit.lead(Lang.get("hotwire.host.check_yes"), () -> {
            this.no = null;
            this.dismiss();
            yes.run();
        })), Kit.push(Lang.get("hotwire.host.check_no"), this::dismiss));
        this.show();
    }

    private void refuse() {
        Runnable answer = this.no;
        this.no = null;
        if (answer != null) {
            answer.run();
        }
    }

    @Override
    public void dismiss() {
        this.refuse();
        super.dismiss();
    }
}
