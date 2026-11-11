package dev.moon.hotwire.ui;

import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

final class ReportPage {
    private static final int WIDE = 360;
    final Part part;
    private final Text title = Kit.text(Lang.get("hotwire.report.page")).big();
    private final Text warning = Kit.text("", Palette.BAD).wrap();
    private final Text where = Kit.text("", Palette.PATH).wrap();
    private final Line fileButtons;
    private final Line result;
    private final Supplier<@Nullable Path> file;
    private @Nullable Path shown;

    ReportPage(Supplier<String> report, Runnable onShowFile, Consumer<String> openLink, Supplier<@Nullable Path> file,
            Consumer<String> onCopy) {
        this.file = file;
        this.fileButtons = Kit.row(Kit.lead(Lang.get("hotwire.report.show_file"), onShowFile),
                Kit.push(Lang.get("hotwire.report.copy_path"), () -> {
                    Path zip = this.shown;
                    if (zip != null) {
                        onCopy.accept(zip.toAbsolutePath().toString());
                        Notices.show(Notice.ofOn(Lang.get("hotwire.report.path_copied"), ""));
                    }
                }), Kit.space()).gap(8);
        this.result = Kit.column(this.warning, this.where, this.fileButtons,
                Kit.text(Lang.get("hotwire.report.send")).wrap(), Kit.dim(Lang.get("hotwire.report.holds")).wrap())
                .gap(8);
        this.result.show(false);
        Part block = Kit.column(this.title, Kit.dim(Lang.get("hotwire.report.about")).wrap(),
                Kit.row(Kit.push(Lang.get("hotwire.report.make"), () -> this.show(Lang.get("hotwire.report.title"),
                        report.get(), "")), Kit.push(Lang.get("hotwire.report.discord"),
                        () -> openLink.accept(SettingsPage.DISCORD)), Kit.space()).gap(8), this.result)
                .gap(10).width(WIDE);

        this.part = Kit.column(Kit.space(), Kit.row(Kit.space(), block, Kit.space()), Kit.space().grow(2))
                .pad(12, 10);
    }

    void show(String title, String where, String warning) {
        this.title.set(title);
        this.where.set(where);
        this.warning.set(warning).show(!warning.isEmpty());

        Path zip = this.file.get();
        String named = zip == null ? "" : String.valueOf(zip.getFileName());
        this.shown = zip != null && where.contains(named) ? zip : null;
        this.fileButtons.show(this.shown != null);
        this.result.show(true);
        this.result.rise();
    }
}
