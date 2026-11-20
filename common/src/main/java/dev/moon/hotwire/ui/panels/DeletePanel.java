package dev.moon.hotwire.ui.panels;

import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.widgets.Kit;
import dev.moon.hotwire.ui.widgets.Palette;
import dev.moon.hotwire.ui.widgets.Push;
import dev.moon.hotwire.ui.widgets.Sheet;
import dev.moon.hotwire.ui.widgets.Text;
import java.util.function.Consumer;

public final class DeletePanel extends Sheet {
    private final Text line = Kit.text("").wrap();
    private final Consumer<String> delete;
    private String id = "";

    @SuppressWarnings("this-escape")
    public DeletePanel(Consumer<String> delete, Runnable cancel) {
        super(320, cancel);
        this.delete = delete;
        this.body.with(this.line);
        Push lead = this.setLead(Kit.lead(Lang.get("hotwire.row.delete"), () -> {
            this.dismiss();
            this.delete.accept(this.id);
        }).ink(Palette.BAD));
        this.buttons.with(Kit.space());
        this.buttons.with(Kit.push(Lang.get("hotwire.button.cancel"), cancel));
        this.buttons.with(lead);
    }

    public void present(String id, String name) {
        this.id = id;
        this.title.set(Lang.get("hotwire.delete.title", name));
        this.line.set(Lang.get("hotwire.delete.body", Lang.get(System.getProperty("os.name", "")
                .startsWith("Windows") ? "hotwire.delete.bin" : "hotwire.delete.trash")));
        this.show();
    }
}
