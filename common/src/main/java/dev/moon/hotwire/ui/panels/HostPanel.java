package dev.moon.hotwire.ui.panels;

import dev.moon.hotwire.seam.SharedChange;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.widgets.Canvas;
import dev.moon.hotwire.ui.widgets.Kit;
import dev.moon.hotwire.ui.widgets.Line;
import dev.moon.hotwire.ui.widgets.Palette;
import dev.moon.hotwire.ui.widgets.Sheet;
import dev.moon.hotwire.ui.widgets.Stripe;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public final class HostPanel extends Sheet {
    private static final int LIST_TALL = 110;

    private final Function<String, String> names;
    private final Function<String, Canvas.@Nullable Image> icons;
    private final Consumer<List<SharedChange>> onMatch;
    private final Runnable onLater;

    private List<SharedChange> shown = List.of();

    private @Nullable Runnable no;

    @SuppressWarnings("this-escape")
    public HostPanel(Consumer<List<SharedChange>> onMatch, Runnable onLater, Function<String, String> names,
            Function<String, Canvas.@Nullable Image> icons) {
        super(400, onLater);
        this.names = names;
        this.icons = icons;
        this.onMatch = onMatch;
        this.onLater = onLater;
    }

    public void open(List<SharedChange> changes) {
        this.no = null;
        this.shown = List.copyOf(changes);
        this.title.set(Lang.get("hotwire.host.title"));
        Line rows = Kit.column().gap(4);
        for (SharedChange change : changes) {
            String mod = change.mod();
            String name = change.kind() == SharedChange.Kind.ADD ? mod : this.names.apply(mod);
            int ink = switch (change.kind()) {
                case ADD -> Palette.GOOD;
                case ON -> Palette.ON;
                case OFF -> Palette.OFF_TINT;
            };
            String verb = switch (change.kind()) {
                case ADD -> Lang.get("hotwire.host.verb_add");
                case ON -> Lang.get("hotwire.host.verb_on");
                case OFF -> Lang.get("hotwire.host.verb_off");
            };
            rows.with(Kit.row(new Stripe(ink, 16), Kit.picture(() -> this.icons.apply(mod), name, 16),
                    Kit.text(verb, ink), Kit.text(name)).gap(7).cross(Line.Cross.CENTER));
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
