package dev.moon.hotwire.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;

final class AddPanel extends JarSheet<AddPreview> {
    static final int HEED = 0xFFE0A45C;
    private static final int MOST_REASONS = 6;
    private static final int LATE_NAMED = 3;

    private final BiConsumer<Path, AddMode> onConfirm;

    @SuppressWarnings("this-escape")
    AddPanel(BiConsumer<Path, AddMode> onConfirm, Runnable onChange, Runnable onCancel) {
        super(440, onChange, onCancel);
        this.onConfirm = onConfirm;
        this.setMark(ModScreen.Icons.ADD, null);
    }

    @Override
    protected AddPreview makeFailed(Throwable cause) {
        return AddPreview.ofRefusal(this.getFileName(), Lang.get("hotwire.failed", cause));
    }

    static String describeHeadsUp(List<String> late) {
        String list = String.join(", ", late.subList(0, Math.min(LATE_NAMED, late.size())));
        if (late.size() > LATE_NAMED) {
            list += Lang.get("hotwire.list.more", late.size() - LATE_NAMED);
        }
        return Lang.get("hotwire.add.heads_up", list);
    }

    @Override
    protected void fill(AddPreview preview, Path jar) {
        AddMode mode = preview.mode();
        boolean refused = mode == AddMode.REFUSED;
        this.setRefused(refused);
        this.setMark(ModScreen.Icons.ADD, preview::icon);
        this.title.set(mode.getLead() + preview.name()).ink(refused ? Palette.BAD : Palette.TEXT);
        this.setSub(preview.version());
        this.body.clear();

        List<String> lines = preview.lines();
        for (String line : lines.subList(0, Math.min(lines.size(), refused ? MOST_REASONS : lines.size()))) {
            this.body.with(Kit.text(line, refused ? Palette.TEXT : Palette.DIM).wrap());
        }
        if (mode == AddMode.NEXT_START) {
            this.body.with(new Part().height(2), Kit.text(Lang.get("hotwire.add.next_start.where")).wrap(),
                    Kit.text(Lang.get("hotwire.add.next_start.undo"), Palette.ON).wrap());
        } else if (!refused && !preview.lateReach().isEmpty()) {
            this.body.with(Kit.row(new Kit.Fill(HEED).width(2), Kit.column(Kit.text(describeHeadsUp(
                    preview.lateReach()), HEED).wrap()).pad(8, 2).grow(1)).cross(Line.Cross.STRETCH));
        }
        String button = mode.findButton();
        this.setButtons(button == null ? null : Kit.lead(button, () -> this.onConfirm.accept(jar, mode)));
    }
}
