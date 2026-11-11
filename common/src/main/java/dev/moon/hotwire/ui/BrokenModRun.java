package dev.moon.hotwire.ui;

import dev.moon.hotwire.plan.BrokenModSearch;
import dev.moon.hotwire.ui.list.ModCatalog;
import dev.moon.hotwire.ui.list.ModStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

final class BrokenModRun {
    enum Phase { SWITCHING, ASKING, RESTORING, DONE }

    record Skip(String id, String why) {
    }

    record Plan(List<String> mods, List<Skip> skipped) {
    }

    private static final int TRIES = 2;
    private static @Nullable BrokenModRun current;

    private final ModScreenGame game;
    private final ModCatalog catalog;
    private final List<String> mods;
    private final BrokenModSearch search;
    private Phase phase = Phase.SWITCHING;
    private int tries;
    private boolean stopped;
    private boolean failed;
    private String refusal = "";

    private List<String> leftOff = List.of();

    private final Set<String> stuck = new LinkedHashSet<>();

    private BrokenModRun(ModScreenGame game, List<String> mods) {
        this.game = game;
        this.catalog = game.getCatalog();
        this.mods = List.copyOf(mods);
        this.search = new BrokenModSearch(mods, id -> findGoingWith(this.catalog, id));
    }

    static @Nullable BrokenModRun getCurrent() {
        return current;
    }

    static void end() {
        current = null;
    }

    private static Set<String> findGoingWith(ModCatalog catalog, String id) {
        Set<String> going = new LinkedHashSet<>(catalog.getNeededBy(id));
        going.addAll(catalog.getContainersOf(id));
        return going;
    }

    static Plan plan(ModScreenGame game, Set<String> pinned) {
        ModCatalog catalog = game.getCatalog();
        Set<String> restartOnly = game.findRestartOnly();
        List<String> mods = new ArrayList<>();
        List<Skip> skipped = new ArrayList<>();
        for (ModStatus mod : game.getMods()) {
            String id = mod.id();
            if (catalog.isHidden(id) || !catalog.isOn(id)) {
                continue;
            }
            String why = null;
            for (String other : BrokenModSearch.findAllGoing(id, one -> findGoingWith(catalog, one))) {
                if (restartOnly.contains(other)) {
                    why = Lang.get("hotwire.find.skip.restart");
                    break;
                }
                if (pinned.contains(other)) {
                    why = Lang.get("hotwire.find.skip.kept");
                    break;
                }
                if (catalog.isProtected(other) || catalog.isLocked(other)) {
                    why = describeSkip(catalog, id, other);
                    break;
                }
            }
            if (why == null) {
                mods.add(id);
            } else {
                skipped.add(new Skip(id, why));
            }
        }
        return new Plan(mods, skipped);
    }

    static List<String> findFirstOff(ModScreenGame game, Plan plan) {
        ModCatalog catalog = game.getCatalog();
        return plan.mods().size() < 2 ? plan.mods()
                : new BrokenModSearch(plan.mods(), id -> findGoingWith(catalog, id)).getOff();
    }

    private static String describeSkip(ModCatalog catalog, String id, String other) {
        if (!other.equals(id)) {
            return Lang.get("hotwire.find.skip.needed", catalog.name(other));
        }
        return Lang.get(catalog.isProtected(id) ? "hotwire.find.skip.never" : "hotwire.find.skip.locked");
    }

    static BrokenModRun start(ModScreenGame game, Plan plan) {
        BrokenModRun run = new BrokenModRun(game, plan.mods());
        current = run;
        game.setSearchSetup(run.mods);
        run.switchTo(Phase.SWITCHING);
        return run;
    }

    Phase getPhase() {
        return this.phase;
    }

    BrokenModSearch getSearch() {
        return this.search;
    }

    List<String> getLeftOff() {
        return this.leftOff;
    }

    Set<String> getStuck() {
        return this.stuck;
    }

    boolean isStopped() {
        return this.stopped;
    }

    boolean isFailed() {
        return this.failed;
    }

    String getRefusal() {
        return this.refusal;
    }

    boolean refuse(String why) {
        if (this.phase != Phase.SWITCHING) {
            return false;
        }
        this.refusal = why;
        return true;
    }

    private Set<String> findWantedOff() {
        return new HashSet<>(this.phase == Phase.SWITCHING || this.phase == Phase.ASKING ? this.search.getOff()
                : this.leftOff);
    }

    private List<String> findToSwitch(boolean on) {
        Set<String> off = this.findWantedOff();
        return this.mods.stream().filter(id -> !this.stuck.contains(id) && off.contains(id) != on
                && this.catalog.isOn(id) != on).toList();
    }

    private void switchTo(Phase waiting) {
        this.phase = waiting;
        List<String> toOn = this.findToSwitch(true);
        List<String> toOff = this.findToSwitch(false);
        this.tries++;
        if (!toOn.isEmpty()) {
            this.game.turnOn(toOn);
        } else if (!toOff.isEmpty()) {
            this.game.turnOff(toOff);
        } else {
            this.arrive();
        }
    }

    private void arrive() {
        if (this.phase == Phase.SWITCHING) {
            this.phase = Phase.ASKING;

            List<String> more = this.search.getOffIfFurther().stream()
                    .filter(id -> !this.stuck.contains(id) && this.catalog.isOn(id)).toList();
            if (!more.isEmpty()) {
                this.game.prepareAhead(more, true);
            }
        } else {
            this.phase = Phase.DONE;
            this.game.setSearchSetup(this.findUnrestored());
        }
    }

    boolean checkSwitch() {
        if (this.phase != Phase.SWITCHING && this.phase != Phase.RESTORING) {
            return false;
        }
        boolean round = this.phase == Phase.SWITCHING;
        this.game.getStayedOn().stream().filter(this.mods::contains).forEach(this.stuck::add);
        if (this.findToSwitch(true).isEmpty() && this.findToSwitch(false).isEmpty()) {
            this.arrive();
            return round;
        }
        if (this.tries < TRIES && (!round || this.refusal.isEmpty())) {
            this.switchTo(this.phase);
        } else if (round) {
            this.failed = true;
            this.restore();
        } else {
            this.arrive();
        }
        return false;
    }

    void answer(boolean stillThere) {
        if (this.phase != Phase.ASKING) {
            return;
        }
        this.search.answer(stillThere);
        this.tries = 0;
        if (!this.search.isDone()) {
            this.switchTo(Phase.SWITCHING);
            return;
        }
        List<String> named = switch (this.search.getResult()) {
            case BrokenModSearch.Result.Found found -> List.of(found.id());
            case BrokenModSearch.Result.Tied tied -> tied.ids();
            case null, default -> List.of();
        };

        Set<String> off = new LinkedHashSet<>();
        named.forEach(id -> off.addAll(BrokenModSearch.findAllGoing(id, one -> findGoingWith(this.catalog, one))));
        this.leftOff = this.mods.stream().filter(off::contains).toList();
        this.restore();
    }

    void stop() {
        if (this.phase == Phase.ASKING || this.phase == Phase.SWITCHING) {
            this.stopped = true;
            this.restore();
        }
    }

    private void restore() {
        this.tries = 0;
        this.switchTo(Phase.RESTORING);
    }

    boolean isNamedOff() {
        return this.leftOff.stream().noneMatch(this.catalog::isOn);
    }

    List<String> findUnrestored() {
        return this.mods.stream().filter(id -> !this.leftOff.contains(id) && !this.catalog.isOn(id)).toList();
    }
}
