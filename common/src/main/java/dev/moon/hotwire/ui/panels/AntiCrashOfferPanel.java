package dev.moon.hotwire.ui.panels;

import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.widgets.Kit;
import dev.moon.hotwire.ui.widgets.Push;
import dev.moon.hotwire.ui.widgets.Sheet;
import java.util.function.Consumer;

public final class AntiCrashOfferPanel extends Sheet {
    private boolean never;

    @SuppressWarnings("this-escape")
    public AntiCrashOfferPanel(String mod, Runnable turnOn, Consumer<Boolean> notNow) {
        super(380, null);
        this.title.set(Lang.get("hotwire.anti_crash.title"));
        this.body.with(Kit.text(Lang.get("hotwire.anti_crash.offer", mod)).wrap());
        this.body.with(Kit.row(Kit.check(() -> this.never, () -> {
            this.never = !this.never;
        }), Kit.dim(Lang.get("hotwire.anti_crash.offer.never"))).gap(6));
        Push lead = this.setLead(Kit.lead(Lang.get("hotwire.anti_crash.offer.turn_on"), () -> {
            this.dismiss();
            turnOn.run();
        }));
        this.buttons.with(Kit.space());
        this.buttons.with(Kit.push(Lang.get("hotwire.anti_crash.offer.not_now"), () -> {
            this.dismiss();
            notNow.accept(this.never);
        }));
        this.buttons.with(lead);
    }
}
