package dev.moon.hotwire.ui;

import java.util.List;
import org.jspecify.annotations.Nullable;

public final class FirstOpenPanel extends Sheet {
    public static final float WAIT = 10;

    private record Sentence(String key, int ink) {
    }

    private static final List<Sentence> SENTENCES = List.of(new Sentence("hotwire.first_open.what", Palette.TEXT),
            new Sentence("hotwire.first_open.work", Palette.TEXT),
            new Sentence("hotwire.first_open.report", Palette.BAD),
            new Sentence("hotwire.first_open.crash", Palette.DIM));

    private static final List<Sentence> CLIENT_SENTENCES = List.of(
            new Sentence("hotwire.client_warning.not_made", Palette.TEXT),
            new Sentence("hotwire.client_warning.no_reports", Palette.BAD));
    private static final List<Sentence> DAWN_SENTENCES = List.of(
            new Sentence("hotwire.client_warning.dawn_why", Palette.TEXT),
            new Sentence("hotwire.client_warning.dawn_unsupported", Palette.BAD),
            new Sentence("hotwire.client_warning.dawn_announcement", Palette.DIM));

    private final Runnable onAccept;
    private final Push accept;
    private final @Nullable Push openHotwire;

    private final @Nullable String credit;
    private float waited;

    private long inSince;

    public FirstOpenPanel(Runnable onAccept) {
        this(onAccept, null);
    }

    @SuppressWarnings("this-escape")
    public FirstOpenPanel(Runnable onAccept, @Nullable Runnable onOpenHotwire) {
        super(380, null);
        this.onAccept = onAccept;
        this.credit = onOpenHotwire == null ? null : Lang.get("hotwire.header.credit", ModsPage.AUTHOR);
        this.accept = this.setLead(Kit.lead(this.getLabel(), () -> this.accept(null)));
        this.openHotwire = onOpenHotwire == null ? null
                : Kit.push(Lang.get("hotwire.first_open.open_hotwire"), () -> this.accept(onOpenHotwire));
        this.buttons.with(Kit.space());
        if (this.openHotwire != null) {
            this.buttons.with(this.openHotwire);
        }
        this.buttons.with(this.accept);
    }

    void present() {
        this.waited = 0;
        this.inSince = 0;
        String client = ModifiedClients.getCurrent();
        this.title.set(client == null ? Lang.get("hotwire.first_open.title")
                : Lang.get("hotwire.client_warning.title", client));
        List<Sentence> sentences = SENTENCES;
        if (client != null) {
            sentences = client.equals(ModifiedClients.DAWN) ? DAWN_SENTENCES : CLIENT_SENTENCES;
        }
        this.body.clear();
        sentences.forEach(sentence -> this.body.with(Kit.text(Lang.get(sentence.key()), sentence.ink()).wrap()));
        if (this.credit != null) {
            this.body.with(Kit.dim(this.credit));
        }
        this.show();
    }

    private void accept(@Nullable Runnable then) {
        if (this.waited >= WAIT) {
            this.onAccept.run();
            this.dismiss();
            if (then != null) {
                then.run();
            }
        }
    }

    String getLabel() {
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
        if (this.openHotwire != null) {
            this.openHotwire.active(this.waited >= WAIT);
        }
        super.step(dt);
    }
}
