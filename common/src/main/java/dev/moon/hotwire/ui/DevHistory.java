package dev.moon.hotwire.ui;

import dev.moon.hotwire.dev.DevCrash;
import dev.moon.hotwire.dev.DevEvent;
import dev.moon.hotwire.dev.DevHost;
import dev.moon.hotwire.dev.DevMixin;
import dev.moon.hotwire.dev.DevModView;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

final class DevHistory {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    final Part history;
    final Part crashes;
    final Part mixins;
    private final DevHost host;
    private final BuildPicker.Opener opener;
    private final Line events = DevCards.makeList();
    private final Line thrown = DevCards.makeList();
    private final Line targets = DevCards.makeList();

    private final Map<Integer, Part> buildCards = new HashMap<>();
    private @Nullable Object shownEvents;
    private @Nullable Object shownCrashes;
    private @Nullable Object shownMixins;

    DevHistory(DevHost host, BuildPicker.Opener opener) {
        this.host = host;
        this.opener = opener;
        this.history = Kit.scroll(this.events);
        this.crashes = Kit.scroll(this.thrown);
        this.mixins = Kit.scroll(this.targets);
    }

    int @Nullable [] findBuildRow(int build) {
        Part found = this.buildCards.get(build);
        return found == null ? null : new int[] {found.getX(), found.getY(), found.getWidth(), found.getHeight()};
    }

    void showHistory(DevModView mod) {
        Object key = List.of(mod.events(), mod.savedBuilds(), mod.runningBuild());
        if (key.equals(this.shownEvents)) {
            return;
        }
        this.shownEvents = key;
        this.events.clear();
        this.buildCards.clear();
        if (mod.events().isEmpty()) {
            this.events.with(DevCards.makeLine(Lang.get("hotwire.dev.window.no_history"), Palette.DIM));
        }
        boolean runningTold = false;
        for (DevEvent event : mod.events()) {
            String clock = LocalTime.ofInstant(Instant.ofEpochMilli(event.at()), ZoneId.systemDefault()).format(CLOCK);
            List<String> lines = new ArrayList<>(event.lines());
            int build = event.build();
            boolean goesBack = false;
            if (build >= 0 && build == mod.runningBuild() && !runningTold) {
                runningTold = true;
                lines.add(Lang.get("hotwire.dev.build.running", build));
            } else if (build >= 0 && mod.savedBuilds().contains(build)) {
                lines.add(Lang.get("hotwire.dev.build.back"));
                goesBack = true;
            }
            Part made = DevCards.makeCard(DevCards.findColor(event.kind()), event.title(), clock, Palette.DIM, lines);
            if (goesBack) {
                this.buildCards.putIfAbsent(build, made.press((button, modifiers) -> this.opener.open(build,
                        BuildPicker.TRY)));
            }
            this.events.with(made);
        }
    }

    void showCrashes(DevModView mod) {
        List<String> whens = new ArrayList<>();
        for (DevCrash crash : mod.crashes()) {
            String ago = DevCards.describeAgo(crash.lastAt());
            whens.add(crash.count() == 1 ? ago : "x" + crash.count() + ", " + ago);
        }
        Object key = List.of(mod.crashes(), whens);
        if (key.equals(this.shownCrashes)) {
            return;
        }
        this.shownCrashes = key;
        this.thrown.clear();
        if (whens.isEmpty()) {
            this.thrown.with(DevCards.makeLine(Lang.get("hotwire.dev.window.no_crashes"), Palette.DIM));
        }
        for (int at = 0; at < whens.size(); at++) {
            this.thrown.with(DevCards.makeCrash(this.host, mod.id(), mod.crashes().get(at), whens.get(at)));
        }
    }

    void showMixins(DevModView mod) {
        if (mod.mixins().equals(this.shownMixins)) {
            return;
        }
        this.shownMixins = mod.mixins();
        this.targets.clear();
        if (mod.mixins().isEmpty()) {
            this.targets.with(DevCards.makeLine(Lang.get("hotwire.dev.window.no_mixins"), Palette.DIM));
        }
        for (DevMixin mixin : mod.mixins()) {
            boolean shared = !mixin.others().isEmpty();
            int ink = shared ? Palette.WARN : Palette.FAINT;
            this.targets.with(DevCards.makeCard(ink, mixin.target(), "", ink, List.of(shared
                    ? Lang.get("hotwire.dev.mixin.also", String.join(", ", mixin.others()))
                    : Lang.get("hotwire.dev.mixin.alone"))));
        }
    }
}
