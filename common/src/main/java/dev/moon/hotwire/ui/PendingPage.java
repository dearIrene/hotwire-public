package dev.moon.hotwire.ui;

import dev.moon.hotwire.update.PendingChange;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

final class PendingPage {
    private static final DateTimeFormatter QUEUED_AT = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ROOT);

    final Part part;
    private final Line list = Kit.column().gap(12);
    private final Scroll scroll = Kit.scroll(this.list);

    private final Part none = Kit.column(Kit.space(), Kit.row(Kit.space(),
            Kit.dim(Lang.get("hotwire.pending.none")), Kit.space()), Kit.space()).grow(1);
    private final Push cleanUpAll;
    private final Push closeGame;

    private List<PendingChange> waiting = List.of();
    private final Consumer<PendingChange> onAct;

    private final Consumer<String> onCleanUp;
    private final Function<String, Canvas.@Nullable Image> icons;
    private final ZoneId zone;

    PendingPage(Consumer<PendingChange> onAct, Consumer<String> onCleanUp,
            Function<String, Canvas.@Nullable Image> icons, ZoneId zone, Consumer<List<PendingChange>> onClose) {
        this.onAct = onAct;
        this.onCleanUp = onCleanUp;
        this.icons = icons;
        this.zone = zone;
        this.cleanUpAll = Kit.push(Lang.get("hotwire.pending.clean_up_all"), () -> onCleanUp.accept(""));
        this.closeGame = Kit.lead(Lang.get("hotwire.pending.close_game"), () -> onClose.accept(this.waiting));
        this.part = Kit.column(Kit.row(Kit.text(Lang.get("hotwire.screen.pending")).big(), Kit.space(),
                this.cleanUpAll), Kit.row(this.closeGame, Kit.space()), this.scroll, this.none).pad(12, 10).gap(10);
    }

    void set(List<PendingChange> changes) {
        this.list.clear();
        this.none.show(changes.isEmpty());
        this.scroll.show(!changes.isEmpty());
        for (Kind kind : Kind.values()) {
            List<PendingChange> ofKind = changes.stream().filter(kind.type::isInstance).toList();
            if (!ofKind.isEmpty()) {
                this.list.with(Kit.row(Kit.text(Lang.get(kind.label), Palette.TEXT),
                        Kit.text(String.valueOf(ofKind.size()), Palette.ON), Kit.rule().grow(1)).gap(6));
                ofKind.forEach(this::addRow);
            }
        }
        this.cleanUpAll.show(changes.stream().anyMatch(PendingChange.Revertable.class::isInstance));
        this.waiting = changes.stream().filter(change -> change instanceof PendingChange.Update
                || change instanceof PendingChange.OffAtStart).toList();
        this.closeGame.show(!this.waiting.isEmpty());
    }

    private enum Kind {
        UPDATES(PendingChange.Update.class, "hotwire.pending.kind.updates"),
        ADDED(PendingChange.Copied.class, "hotwire.pending.kind.added"),
        OFF(PendingChange.OffAtStart.class, "hotwire.pending.kind.off"),
        EARLIER(PendingChange.Revertable.class, "hotwire.pending.kind.earlier");

        private final Class<? extends PendingChange> type;
        private final String label;

        Kind(Class<? extends PendingChange> type, String label) {
            this.type = type;
            this.label = label;
        }
    }

    private void addRow(PendingChange change) {
        Line words = Kit.column(Kit.text(change.name())).gap(3);
        this.describe(change, words);
        words.grow(1);
        Line row = Kit.row(Kit.picture(() -> this.icons.apply(change.modId()), change.name(), 16), words).gap(8);
        if (change instanceof PendingChange.Revertable back) {
            row.with(Kit.push(Lang.get("hotwire.pending.clean_up"), () -> this.onCleanUp.accept(back.modId())));
        }
        this.list.with(row.with(Kit.push(findLabel(change), () -> this.onAct.accept(change))));
    }

    private static String findLabel(PendingChange change) {
        return switch (change) {
            case PendingChange.Update update -> Lang.get("hotwire.button.cancel");
            case PendingChange.Copied copied -> Lang.get("hotwire.pending.undo");
            case PendingChange.OffAtStart off -> Lang.get("hotwire.pending.undo");
            case PendingChange.Revertable back -> Lang.get("hotwire.pending.revert", back.oldVersion());
        };
    }

    private void describe(PendingChange change, Line words) {
        String queued = change instanceof PendingChange.Update update && update.queuedAtMillis() != 0
                ? Lang.get("hotwire.pending.queued", QUEUED_AT.format(Instant.ofEpochMilli(update.queuedAtMillis())
                        .atZone(this.zone))) : "";
        words.with(Kit.dim(describeAtClose(change) + queued).wrap());
        if (change instanceof PendingChange.Update update && !update.reason().isEmpty()) {
            words.with(Kit.text(Lang.get("hotwire.pending.close_failed", update.reason()), Palette.BAD).wrap());
        }
    }

    static String describeAtClose(PendingChange change) {
        return switch (change) {
            case PendingChange.Update update -> update.oldJar().isEmpty()
                    ? Lang.get("hotwire.pending.update", update.newJar())
                    : Lang.get("hotwire.pending.update_replacing", update.newJar(), update.oldJar());
            case PendingChange.Copied copied -> Lang.get("hotwire.pending.copied", copied.jar());
            case PendingChange.OffAtStart off -> Lang.get("hotwire.pending.off_at_start");
            case PendingChange.Revertable back -> Lang.get("hotwire.pending.revertable", back.oldVersion());
        };
    }
}
