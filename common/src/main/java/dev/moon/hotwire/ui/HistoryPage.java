package dev.moon.hotwire.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

final class HistoryPage {
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    final Part part;
    private final Line list = Kit.column().gap(12);
    private final Scroll scroll = Kit.scroll(this.list);

    private final Part none = Kit.column(Kit.space(), Kit.row(Kit.space(),
            Kit.dim(Lang.get("hotwire.history.none")), Kit.space()), Kit.space()).grow(1);
    private final ZoneId zone;

    HistoryPage(ZoneId zone) {
        this.zone = zone;
        this.part = Kit.column(Kit.row(Kit.text(Lang.get("hotwire.screen.history")).big(), Kit.space(),
                Kit.dim(Lang.get("hotwire.history.session"))), this.scroll, this.none).pad(12, 10).gap(10);
    }

    void set(List<Notices.Told> told, @Nullable String last, Runnable undo) {
        this.list.clear();
        this.none.show(told.isEmpty() && last == null);
        this.scroll.show(!told.isEmpty() || last != null);
        if (last != null) {
            this.list.with(Kit.row(Kit.text(Lang.get("hotwire.history.last"), Palette.TEXT), Kit.rule().grow(1))
                    .gap(6));
            this.list.with(Kit.row(Kit.text(last).wrap().grow(1), Kit.push(Lang.get("hotwire.notice.undo"), undo))
                    .gap(10).cross(Line.Cross.CENTER));
        }
        for (Section section : Section.values()) {
            List<Notices.Told> ofKind = told.stream().filter(one -> one.notice().kind() == section.kind
                    || one.notice().kind() == section.also).toList();
            if (!ofKind.isEmpty()) {
                this.list.with(Kit.row(Kit.text(Lang.get(section.label), Palette.TEXT),
                        Kit.text(String.valueOf(ofKind.size()), section.ink), Kit.rule().grow(1)).gap(6));
                ofKind.forEach(one -> this.addRow(one, section.ink));
            }
        }
    }

    private enum Section {
        PROBLEMS("hotwire.history.kind.problems", Palette.BAD, Notice.Kind.BAD, Notice.Kind.BAD),
        ASKS("hotwire.history.kind.asks", Palette.WARN, Notice.Kind.ASK, Notice.Kind.ASK),
        RECORDS("hotwire.history.kind.records", Palette.GOLD, Notice.Kind.RECORD, Notice.Kind.RECORD),
        DONE("hotwire.history.kind.done", Palette.ON, Notice.Kind.DONE, Notice.Kind.ON);

        private final String label;
        private final int ink;
        private final Notice.Kind kind;
        private final Notice.Kind also;

        Section(String label, int ink, Notice.Kind kind, Notice.Kind also) {
            this.label = label;
            this.ink = ink;
            this.kind = kind;
            this.also = also;
        }
    }

    private void addRow(Notices.Told told, int ink) {
        Notice notice = told.notice();
        Line words = Kit.column(Kit.text(notice.title(), notice.kind() == Notice.Kind.DONE
                || notice.kind() == Notice.Kind.ON ? Palette.TEXT : ink).wrap()).gap(3);
        if (!notice.detail().isBlank()) {
            words.with(Kit.dim(notice.detail()).wrap());
        }
        words.grow(1);
        this.list.with(Kit.row(Kit.text(AT.format(Instant.ofEpochMilli(told.atMillis()).atZone(this.zone)),
                Palette.FAINT), words).gap(10));
    }
}
