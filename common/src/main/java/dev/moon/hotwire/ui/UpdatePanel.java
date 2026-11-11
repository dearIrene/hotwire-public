package dev.moon.hotwire.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;

final class UpdatePanel extends JarSheet<UpdatePreview> {
    private static final int MOST_LINES = 8;
    private static final int CARD = 0x14FFFFFF;

    private final BiConsumer<Path, UpdateMethod> onUpdate;

    @SuppressWarnings("this-escape")
    UpdatePanel(BiConsumer<Path, UpdateMethod> onUpdate, Runnable onChange, Runnable onCancel) {
        super(420, onChange, onCancel);
        this.onUpdate = onUpdate;
        this.setMark(ModScreen.Icons.UPDATES, null);
    }

    @Override
    protected UpdatePreview makeFailed(Throwable cause) {
        return new UpdatePreview.Unreadable(this.getFileName());
    }

    @Override
    protected void fill(UpdatePreview preview, Path jar) {
        this.setRefused(!(preview instanceof UpdatePreview.Ready));
        this.body.clear();
        switch (preview) {
            case UpdatePreview.Ready ready -> this.fillReady(ready, jar);
            case UpdatePreview.SameVersion same -> this.refuse(same.name(),
                    Lang.get("hotwire.update.same_version", same.name(), same.version()));
            case UpdatePreview.Unreadable unreadable -> this.refuse(unreadable.fileName(),
                    Lang.get("hotwire.update.unreadable"));
            case UpdatePreview.Refused refused -> this.refuse(refused.name(), refused.reason());
        }
    }

    private void refuse(String name, String why) {
        this.title.set(Lang.get("hotwire.update.lead.refused") + name).ink(Palette.BAD);
        this.body.with(Kit.text(why).wrap());
        this.setButtons(null);
    }

    private void fillReady(UpdatePreview.Ready ready, Path jar) {
        boolean now = ready.method() == UpdateMethod.IN_PLACE;
        this.title.set(Lang.get("hotwire.update.lead.update") + ready.name()).ink(Palette.TEXT);

        this.body.with(makeCard(ready, Lang.get("hotwire.update.installed"), ready.installedVersion(), Palette.BAD),
                Kit.row(Kit.space(), Kit.dim("↓"), Kit.space()),
                makeCard(ready, ready.fileName(), ready.newVersion(), Palette.GOOD), new Part().height(2),
                Kit.text(Lang.get(now ? "hotwire.update.when.now" : "hotwire.update.when.restart"),
                        now ? Palette.ON : Palette.BAD).wrap());
        List<String> lines = ready.lines();

        boolean more = lines.size() > MOST_LINES;
        lines.subList(0, more ? MOST_LINES - 1 : lines.size()).forEach(line -> this.body.with(Kit.dim(line).wrap()));
        if (more) {
            this.body.with(Kit.dim(Lang.get("hotwire.panel.more_in_log")));
        }
        this.setButtons(Kit.lead(Lang.get(now ? "hotwire.update.button.now" : "hotwire.update.button.restart"),
                () -> this.onUpdate.accept(jar, ready.method())));
    }

    private static Part makeCard(UpdatePreview.Ready ready, String source, String version, int ink) {
        return Kit.row(new Kit.Fill(ink).width(2), Kit.row(Kit.picture(ready::icon, ready.name(), 24),
                Kit.column(Kit.text(ready.name()), Kit.dim(source)).gap(3).grow(1), Kit.text(version, ink)).gap(8)
                .pad(8, 6).cross(Line.Cross.CENTER).grow(1)).cross(Line.Cross.STRETCH).face(CARD);
    }
}
