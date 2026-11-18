package dev.moon.hotwire.ui.panels;

import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.widgets.Kit;
import dev.moon.hotwire.ui.widgets.Palette;
import dev.moon.hotwire.ui.widgets.Push;
import dev.moon.hotwire.ui.widgets.Sheet;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class DownloadsWarningPanel extends Sheet {
    private static final float WAIT = FirstOpenPanel.WAIT;

    private record Sentence(String key, int ink) {
    }

    private static final List<Sentence> SENTENCES = List.of(
            new Sentence("hotwire.downloads_warning.source", Palette.TEXT),
            new Sentence("hotwire.downloads_warning.mind", Palette.BAD),
            new Sentence("hotwire.downloads_warning.host", Palette.DIM));

    private final Runnable onAccept;
    private final Push accept;
    private @Nullable Runnable then;
    private float waited;

    private long inSince;

    @SuppressWarnings("this-escape")
    public DownloadsWarningPanel(Runnable onAccept, Runnable onCancel) {
        super(400, onCancel);
        this.onAccept = onAccept;
        this.title.set(Lang.get("hotwire.downloads_warning.title"));
        this.accept = this.setLead(Kit.lead(this.getLabel(), this::accept));
        this.buttons.with(Kit.space(), Kit.push(Lang.get("hotwire.host.later"), onCancel), this.accept);
    }

    public void present(Runnable then) {
        this.then = then;
        this.waited = 0;
        this.inSince = 0;
        this.body.clear();
        SENTENCES.forEach(sentence -> this.body.with(Kit.text(Lang.get(sentence.key()), sentence.ink()).wrap()));
        this.show();
    }

    private void accept() {
        if (this.waited < WAIT) {
            return;
        }
        this.onAccept.run();
        this.dismiss();
        Runnable after = this.then;
        this.then = null;
        if (after != null) {
            after.run();
        }
    }

    private String getLabel() {
        int left = (int) Math.ceil(WAIT - this.waited);
        return left > 0 ? Lang.get("hotwire.first_open.accept_wait", left) : Lang.get("hotwire.first_open.accept");
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
        this.accept.label(this.getLabel()).active(this.waited >= WAIT);
        super.step(dt);
    }
}
