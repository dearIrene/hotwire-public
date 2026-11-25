package dev.moon.hotwire.ui.panels;

import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.ui.widgets.Canvas;
import dev.moon.hotwire.ui.widgets.Kit;
import dev.moon.hotwire.ui.widgets.Sheet;
import dev.moon.hotwire.ui.widgets.Text;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

public final class CrashPanel extends Sheet {
    private final Text line = Kit.text("").wrap();
    private final Text proof = Kit.dim("").wrap();
    private final Function<String, Canvas.@Nullable Image> icons;
    private final Predicate<String> on;
    private final Consumer<Caught> turnOff;
    private final Consumer<Caught> report;
    private final Runnable close;

    public record Caught(String id, String name, String title, String line, String proof, String where) {
    }

    @SuppressWarnings("this-escape")
    public CrashPanel(Function<String, Canvas.@Nullable Image> icons, Predicate<String> on, Consumer<Caught> turnOff,
            Consumer<Caught> report, Runnable close) {
        super(380, close);
        this.icons = icons;
        this.on = on;
        this.turnOff = turnOff;
        this.report = report;
        this.close = close;
        this.body.with(this.line, this.proof);
    }

    public void present(Caught caught) {
        this.setRefused(true);
        this.setMark(ModScreen.Icons.MODS, () -> this.icons.apply(caught.id()));
        this.title.set(caught.name());
        this.setSub(caught.title());
        this.line.set(caught.line());
        this.proof.set(caught.proof());
        String off = Lang.get(this.on.test(caught.id()) ? "hotwire.crash.turn_off" : "hotwire.crash.keep_off",
                caught.name());
        this.buttons.clear().with(this.setLead(Kit.lead(off, () -> {
            this.dismiss();
            this.turnOff.accept(caught);
        })), Kit.push(Lang.get("hotwire.find.report"), () -> {
            this.dismiss();
            this.report.accept(caught);
        }), Kit.space(), Kit.push(Lang.get("hotwire.button.close"), this.close));
        this.show();
    }
}
