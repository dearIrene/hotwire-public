package dev.moon.hotwire.ui;

final class SelfUpdatePanel extends Sheet {
    private static final int CHANGES_TALL = 150;

    private enum Mode { NEWER, URGENT, READY }

    private final Runnable onUpdate;
    private final Runnable onLater;
    private final Runnable onCloseGame;
    private final Markdown changed = new Markdown();
    private final Scroll scroll = Kit.scroll(this.changed);
    private Mode mode = Mode.NEWER;
    private String version = "";
    private String message = "";
    private String changes = "";

    private boolean shown;

    SelfUpdatePanel(Runnable onUpdate, Runnable onLater, Runnable onCloseGame) {
        super(480, onLater);
        this.onUpdate = onUpdate;
        this.onLater = onLater;
        this.onCloseGame = onCloseGame;
        this.scroll.capAt(CHANGES_TALL);
        this.setMark(ModScreen.Icons.UPDATES);
    }

    void setChanges(String changes) {
        this.changes = changes;
        if (this.open()) {
            this.fill();
        }
    }

    void presentOnce(String version) {
        if (this.shown || version.isEmpty() || this.mode != Mode.NEWER) {
            return;
        }
        this.shown = true;
        this.version = version;
        this.present();
    }

    void presentUrgent(String version, String message) {
        if (this.mode != Mode.READY) {
            this.mode = Mode.URGENT;
            this.version = version;
            this.message = message;
        }
        this.present();
    }

    void showReady(String version) {
        if (this.mode == Mode.URGENT) {
            this.mode = Mode.READY;
            this.version = version;
            this.present();
        }
    }

    void scrollTo(int scrolled) {
        this.scroll.scrollTo(scrolled);
    }

    private void present() {
        this.scroll.reset();
        this.fill();
        this.show();
    }

    private void fill() {
        boolean urgent = this.mode == Mode.URGENT;
        this.setRefused(urgent);
        this.title.set(switch (this.mode) {
            case NEWER -> Lang.get("hotwire.self_update.title", this.version);
            case URGENT -> "! " + Lang.get("hotwire.self_update.urgent_title");
            case READY -> Lang.get("hotwire.self_update.ready_title");
        }).ink(urgent ? Palette.BAD : Palette.TEXT);
        this.body.clear();
        if (urgent && !this.message.isEmpty()) {
            this.body.with(Kit.row(new Kit.Fill(Palette.OFF_TINT).width(2), Kit.column(Kit.text(this.message)
                    .wrap()).pad(8, 4).grow(1)).cross(Line.Cross.STRETCH));
        }
        if (this.mode != Mode.READY && !this.changes.isBlank()) {
            this.changed.set(this.changes);
            this.body.with(Kit.text(Lang.get("hotwire.self_update.changes"), Palette.ACCENT_SOFT),
                    Kit.column(this.scroll).pad(8, 6).face(Palette.HAIRLINE));
        }
        Push later = Kit.push(Lang.get("hotwire.self_update.later"), this.onLater);
        switch (this.mode) {
            case NEWER -> {
                this.body.with(Kit.row(new Kit.Fill(Palette.BAD).width(2), Kit.column(
                        Kit.text(Lang.get("hotwire.self_update.no_live"), Palette.BAD).wrap(),
                        Kit.dim(Lang.get("hotwire.self_update.next_start")).wrap()).gap(3).pad(8, 2).grow(1))
                        .cross(Line.Cross.STRETCH));
                this.setLead(Kit.lead(Lang.get("hotwire.self_update.button"), () -> {
                    this.onUpdate.run();
                    this.dismiss();
                }));
            }
            case URGENT -> {
                this.body.with(Kit.dim(Lang.get("hotwire.self_update.urgent_next_start", this.version)).wrap());
                later.label(Lang.get("hotwire.self_update.ignore"));
                this.setLead(Kit.lead(Lang.get("hotwire.self_update.urgent_button"), this.onUpdate)
                        .ink(Palette.OFF_TINT));
            }
            case READY -> {
                this.body.with(Kit.dim(Lang.get("hotwire.self_update.ready_line", this.version)).wrap());
                later.label(Lang.get("hotwire.self_update.keep_playing"));
                this.setLead(Kit.lead(Lang.get("hotwire.self_update.close_game"), this.onCloseGame));
            }
        }
        this.buttons.clear().with(Kit.space(), this.getLead(), later);
    }
}
