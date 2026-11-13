package dev.moon.hotwire.ui;

import dev.moon.hotwire.switching.BestTimes;
import dev.moon.hotwire.switching.SwitchHistory;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

final class HistoryLines {
    private static final DateTimeFormatter TODAY = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter EARLIER = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ROOT);

    private HistoryLines() {
    }

    static void fill(Line into, List<SwitchHistory.Entry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        into.with(Kit.text(Lang.get("hotwire.history.title"), Palette.FAINT));
        entries.forEach(entry -> into.with(Kit.dim(describe(entry, now)).wrap()));
    }

    static void fillBest(Line into, BestTimes.@Nullable Best off, BestTimes.@Nullable Best on) {
        if (off == null && on == null) {
            return;
        }
        into.with(Kit.text(Lang.get("hotwire.record.best"), Palette.FAINT));
        if (off != null) {
            into.with(Kit.text(describeBest("hotwire.record.off", off), Palette.GOLD).wrap());
        }
        if (on != null) {
            into.with(Kit.text(describeBest("hotwire.record.on", on), Palette.GOLD).wrap());
        }
    }

    private static String describeBest(String key, BestTimes.Best best) {
        String time = Lang.get(key, BestTimes.format(best.millis()));
        return best.together() < 2 ? time : time + " " + Lang.get("hotwire.record.together", best.together() - 1);
    }

    static String describe(SwitchHistory.Entry entry, ZonedDateTime now) {
        ZonedDateTime at = Instant.ofEpochMilli(entry.atMillis()).atZone(now.getZone());
        String time = (at.toLocalDate().equals(now.toLocalDate()) ? TODAY : EARLIER).format(at);
        String by = switch (entry.cause()) {
            case PLAYER -> Lang.get("hotwire.history.by.player");
            case MOD -> entry.by().isEmpty() ? Lang.get("hotwire.history.by.other_mod")
                    : Lang.get("hotwire.history.by.mod", entry.by());
            case PROFILE -> Lang.get("hotwire.history.by.profile", entry.by());
            case ANTI_CRASH -> Lang.get("hotwire.history.by.anti_crash");
            case UPDATE -> Lang.get("hotwire.history.by.update");
        };
        return Lang.get(entry.off() ? "hotwire.history.off" : "hotwire.history.on", time, by);
    }
}
