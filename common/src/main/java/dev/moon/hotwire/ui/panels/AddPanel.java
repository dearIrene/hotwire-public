package dev.moon.hotwire.ui.panels;

import dev.moon.hotwire.ui.AddMode;
import dev.moon.hotwire.ui.AddPreview;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.ui.widgets.Kit;
import dev.moon.hotwire.ui.widgets.Line;
import dev.moon.hotwire.ui.widgets.Palette;
import dev.moon.hotwire.ui.widgets.Part;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

public final class AddPanel extends JarSheet<AddPreview> {
    static final int HEED = 0xFFE0A45C;
    private static final int MOST_REASONS = 6;
    private static final int LATE_NAMED = 3;

    private final BiConsumer<Path, AddMode> onConfirm;
    private final Predicate<Path> asked;
    private final Runnable onSettings;

    @SuppressWarnings("this-escape")

    public AddPanel(BiConsumer<Path, AddMode> onConfirm, Runnable onChange, Runnable onCancel, Predicate<Path> asked,
            Runnable onSettings) {
        super(440, onChange, onCancel);
        this.onConfirm = onConfirm;
        this.asked = asked;
        this.onSettings = onSettings;
        this.setMark(ModScreen.Icons.ADD, null);
    }

    @Override
    protected AddPreview makeFailed(Throwable cause) {
        return AddPreview.ofRefusal(this.getFileName(), Lang.get("hotwire.failed", cause));
    }

    static boolean isPlain(AddPreview preview) {
        return preview.mode() == AddMode.NOW && preview.lateReach().isEmpty();
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
        boolean plain = isPlain(preview);
        if (plain && !this.asked.test(jar)) {
            this.onConfirm.accept(jar, mode);
            return;
        }
        boolean refused = mode == AddMode.REFUSED;
        this.setRefused(refused);
        this.setMark(ModScreen.Icons.ADD, preview::icon);
        this.title.set(mode.getLead() + preview.name()).ink(refused ? Palette.BAD : Palette.TEXT);
        this.setSub(preview.version());
        this.body.clear();

        List<String> lines = preview.lines();

        int shown = Math.min(lines.size(), refused ? MOST_REASONS : lines.size());
        for (int at = 0; at < shown; at++) {
            String line = lines.get(at);
            int cut = line.lastIndexOf(". ");
            if (refused) {
                this.body.with(Kit.text(line).wrap());
            } else if (at < shown - 1) {
                this.body.with(Kit.text(line, Palette.FAINT).wrap());
            } else if (mode == AddMode.SAVE_AND_REOPEN && cut > 0) {
                this.body.with(new Part().height(2), Kit.dim(line.substring(0, cut + 1)).wrap(),
                        Kit.text(line.substring(cut + 2), Palette.ON).wrap());
            } else {
                this.body.with(Kit.text(line, mode == AddMode.NOW ? Palette.FAINT : Palette.DIM).wrap());
            }
        }
        if (mode == AddMode.NEXT_START) {
            this.body.with(new Part().height(2), Kit.text(Lang.get("hotwire.add.next_start.where")).wrap(),
                    Kit.text(Lang.get("hotwire.add.next_start.undo"), Palette.ON).wrap());
        } else if (!refused && !preview.lateReach().isEmpty()) {
            this.body.with(Kit.row(new Kit.Fill(HEED).width(2), Kit.column(Kit.text(describeHeadsUp(
                    preview.lateReach()), HEED).wrap()).pad(8, 2).grow(1)).cross(Line.Cross.STRETCH));
        }
        if (plain) {
            this.body.with(new Part().height(2), Kit.push(Lang.get("hotwire.add.ask_setting"), this.onSettings));
        }
        String button = mode.findButton();
        this.setButtons(button == null ? null : Kit.lead(button, () -> this.onConfirm.accept(jar, mode)));
    }
}
