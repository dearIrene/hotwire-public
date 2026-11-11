package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public final class CarriedPanel extends Sheet {
    private final Function<String, Canvas.@Nullable Image> icons;
    private final Function<String, String> names;
    private List<String> ids = List.of();

    public CarriedPanel(Consumer<List<String>> onConfirm, Runnable onCancel,
            Function<String, Canvas.@Nullable Image> icons, Function<String, String> names) {
        super(400, onCancel);
        this.icons = icons;
        this.names = names;
        this.title.set(Lang.get("hotwire.carried.title"));
        this.buttons.with(Kit.space(), this.setLead(Kit.lead(Lang.get("hotwire.carried.both"),
                () -> onConfirm.accept(this.ids))), Kit.push(Lang.get("hotwire.carried.no"), onCancel));
    }

    public void open(String id, List<String> containers) {
        String container = containers.getFirst();
        List<String> all = new ArrayList<>(containers);
        all.addFirst(id);
        this.ids = List.copyOf(all);
        String name = this.names.apply(container);
        this.body.clear().with(Kit.row(Kit.picture(() -> this.icons.apply(container), name, 32), Kit.column(
                Kit.text(Lang.get("hotwire.carried.copy", name), Palette.ACCENT).wrap(),
                Kit.text(Lang.get("hotwire.carried.ask")).wrap()).gap(6).grow(1)).gap(10));
        this.show();
    }
}
