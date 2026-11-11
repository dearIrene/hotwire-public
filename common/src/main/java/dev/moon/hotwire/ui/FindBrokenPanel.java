package dev.moon.hotwire.ui;

import dev.moon.hotwire.plan.BrokenModSearch;
import dev.moon.hotwire.ui.SearchSquares.Look;
import dev.moon.hotwire.ui.list.ModCatalog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

final class FindBrokenPanel extends Sheet {
    private static final int SHOWN = 16;
    private static final int SHOWN_FOUND = 10;
    static final float BEAT = 2.6f;
    private static final List<String> BEATS = List.of("hotwire.find.beat.half", "hotwire.find.beat.check",
            "hotwire.find.beat.again", "hotwire.find.beat.one");
    private static final int PINS_TALL = 120;
    private static final int PINS_WIDE = 230;

    private static final int MOST_NAMED = 6;

    private static final float STUCK = 3;
    private static final String CARD = "find-broken";

    private enum View { OPENING, ROUND, CUT_OFF }

    private final ModScreen screen;
    private final ModScreenGame game;
    private final ModCatalog catalog;
    private final SearchSquares squares = new SearchSquares();
    private final Text caption = Kit.text("");
    private View view = View.OPENING;
    private BrokenModRun.Plan plan = new BrokenModRun.Plan(List.of(), List.of());

    private final Set<String> pins = new LinkedHashSet<>();
    private final Line list = Kit.column().gap(3);
    private final TextField filter = new TextField(Lang.get("hotwire.screen.search"), typed -> this.fillList(),
            () -> { }, this::dismiss);
    private final Text summary = Kit.text("").wrap();
    private final Push begin = Kit.lead(Lang.get("hotwire.find.start"), this::start);
    private float clock;
    private int beat = -1;
    private float idle;
    private boolean stoppable;

    FindBrokenPanel(ModScreen screen, ModScreenGame game) {
        super(560, null);
        this.screen = screen;
        this.game = game;
        this.catalog = game.getCatalog();
    }

    void present(Set<String> pinned) {
        if (BrokenModRun.getCurrent() != null) {
            this.resume();
            return;
        }
        this.view = View.OPENING;
        this.pins.clear();
        this.pins.addAll(this.game.getSearchPins());
        if (this.pins.addAll(pinned)) {
            this.game.setSearchPins(List.copyOf(this.pins));
        }
        this.setRefused(false);
        this.setMark(ModScreen.Icons.MODS);
        this.title.set(Lang.get("hotwire.find.title"));
        this.setSub(Lang.get("hotwire.find.sub"));
        this.showBeat(0);
        this.filter.setText("");
        Part shown = Kit.column(this.squares, this.caption, Kit.rule(), this.summary).gap(6).grow(1);
        Line pinColumn = Kit.column(Kit.dim(Lang.get("hotwire.find.keep")), Kit.fit(this.filter).height(18),
                Kit.column(Kit.scroll(this.list).capAt(PINS_TALL)).pad(6, 4).face(Palette.HAIRLINE)).gap(5);
        this.body.clear().with(Kit.row(shown, pinColumn.width(PINS_WIDE)).gap(12).cross(Line.Cross.START));
        this.buttons.clear().with(Kit.space(), this.setLead(this.begin),
                Kit.push(Lang.get("hotwire.find.not_now"), this::dismiss));
        this.planAgain();
        this.show();
    }

    private void planAgain() {
        this.plan = BrokenModRun.plan(this.game, this.pins);
        this.game.prepareAhead(BrokenModRun.findFirstOff(this.game, this.plan), true);
        int count = this.plan.mods().size();
        String words = count == 1 ? Lang.get("hotwire.find.facts_one")
                : Lang.get("hotwire.find.facts", count, BrokenModSearch.countRounds(count));
        this.summary.set(count == 0 ? Lang.get("hotwire.find.nothing") : words);
        this.begin.active(count > 0);
        this.fillList();
    }

    private void fillList() {
        Map<String, String> staying = new HashMap<>();
        this.plan.skipped().forEach(skip -> staying.put(skip.id(), skip.why()));
        String typed = this.filter.getText().strip().toLowerCase(Locale.ROOT);
        this.list.clear();
        this.game.getMods().stream().filter(mod -> !this.catalog.isHidden(mod.id()) && this.catalog.isOn(mod.id()))
                .filter(mod -> mod.name().toLowerCase(Locale.ROOT).contains(typed))
                .sorted(Comparator.comparing(mod -> mod.name().toLowerCase(Locale.ROOT))).forEach(mod -> {
                    String id = mod.id();
                    Part icon = Kit.picture(() -> this.game.findIcon(id), mod.name(), 10);
                    String why = staying.get(id);
                    if (why == null || this.pins.contains(id)) {
                        this.list.with(Kit.row(Kit.check(() -> this.pins.contains(id), () -> this.turnPin(id)), icon,
                                Kit.text(mod.name()).grow(1)).gap(5).cross(Line.Cross.CENTER));
                    } else {
                        this.list.with(Kit.row(new Part().width(Kit.Check.SIDE), icon, Kit.text(mod.name(),
                                Palette.FAINT).grow(1), Kit.text(why, Palette.FAINT).spare()).gap(5)
                                .cross(Line.Cross.CENTER));
                    }
                });
    }

    private void turnPin(String id) {
        if (!this.pins.remove(id)) {
            this.pins.add(id);
        }
        this.game.setSearchPins(List.copyOf(this.pins));
        this.planAgain();
    }

    void showBeat(int beat) {
        this.beat = beat;
        this.clock = beat * BEAT;
        List<Look> looks = new ArrayList<>();
        for (int index = 0; index < SHOWN; index++) {
            boolean first = index < SHOWN / 2;
            looks.add(switch (beat) {
                case 0, 1 -> first ? Look.OFF : Look.ON;
                case 2 -> first ? Look.GONE : findLook(true, index < SHOWN * 3 / 4);
                default -> index == SHOWN_FOUND ? Look.FOUND : Look.GONE;
            });
        }
        this.squares.set(looks).ask(beat == 1);
        this.caption.set(Lang.get(BEATS.get(beat), BrokenModSearch.countRounds(Math.max(2, this.plan.mods().size()))));
    }

    private static Look findLook(boolean suspect, boolean off) {
        if (!suspect) {
            return Look.GONE;
        }
        return off ? Look.OFF : Look.ON;
    }

    private void start() {
        this.view = View.ROUND;
        BrokenModRun.start(this.game, this.plan);
        this.showRun();
    }

    void resume() {
        Notices.drop(new Notice(Notice.Kind.ASK, CARD, "", CARD));
        if (BrokenModRun.getCurrent() != null) {
            this.showRun();
            return;
        }
        Set<String> installed = new HashSet<>();
        this.game.getMods().forEach(mod -> installed.add(mod.id()));
        List<String> off = this.game.getSearchSetup().stream().filter(installed::contains)
                .filter(id -> !this.catalog.isOn(id)).toList();
        if (off.isEmpty()) {
            if (!this.game.getSearchSetup().isEmpty()) {
                this.game.setSearchSetup(List.of());
            }
            return;
        }
        this.view = View.CUT_OFF;
        this.setRefused(false);
        this.setMark(ModScreen.Icons.MODS);
        this.title.set(Lang.get("hotwire.find.cut_off.title"));
        this.setSub("");
        this.body.clear().with(Kit.text(Lang.get("hotwire.find.cut_off.line", off.size())).wrap(),
                Kit.dim(this.joinNames(off)).wrap());
        this.buttons.clear().with(Kit.space(), this.setLead(Kit.lead(Lang.get("hotwire.find.cut_off.turn_on"), () -> {
            this.game.setSearchSetup(List.of());
            this.dismiss();
            this.game.turnOn(off);
        })), Kit.push(Lang.get("hotwire.find.cut_off.leave"), () -> {
            this.game.setSearchSetup(List.of());
            this.dismiss();
        }));
        this.show();
    }

    boolean refuse(String why) {
        BrokenModRun run = BrokenModRun.getCurrent();
        return run != null && run.refuse(why);
    }

    void onSwitched() {
        BrokenModRun run = BrokenModRun.getCurrent();
        if (run == null) {
            return;
        }
        boolean asks = run.checkSwitch();
        this.showRun();
        if (asks) {
            this.leave();
        }
    }

    private void leave() {
        BrokenModRun run = BrokenModRun.getCurrent();
        if (run == null || run.getPhase() != BrokenModRun.Phase.ASKING) {
            return;
        }
        BrokenModSearch search = run.getSearch();
        int round = search.getRound();
        int rounds = round - 1 + search.countRoundsLeft();
        Notices.show(new Notice(Notice.Kind.ASK, Lang.get("hotwire.find.card", round, rounds),
                Lang.get("hotwire.find.card_detail"), CARD, (round - 1) / (float) rounds));
        this.game.onDone();
    }

    private String joinNames(List<String> ids) {
        List<String> names = new ArrayList<>(ids.stream().limit(MOST_NAMED).map(this.catalog::name).toList());
        if (ids.size() > MOST_NAMED) {
            names.add(Lang.get("hotwire.find.more", ids.size() - MOST_NAMED));
        }
        return String.join(", ", names);
    }

    private void showRun() {
        BrokenModRun run = BrokenModRun.getCurrent();
        if (run == null) {
            return;
        }
        this.view = View.ROUND;
        this.idle = 0;
        this.stoppable = false;
        BrokenModSearch search = run.getSearch();
        Set<String> off = new HashSet<>(search.getOff());
        Set<String> suspects = new HashSet<>(search.getSuspects());
        BrokenModSearch.Result result = search.getResult();
        List<Look> looks = new ArrayList<>();
        for (String id : search.getOrder()) {
            if (result != null) {
                looks.add(run.getPhase() == BrokenModRun.Phase.DONE && !run.isFailed() && !run.isStopped()
                        && isNamed(result, id) ? Look.FOUND : Look.GONE);
            } else {
                looks.add(findLook(suspects.contains(id), off.contains(id)));
            }
        }
        this.squares.set(looks).ask(false);
        this.setRefused(false);
        this.setMark(ModScreen.Icons.MODS);
        this.setSub("");
        this.setLead(null);
        this.body.clear().with(this.squares);
        this.buttons.clear();
        int round = search.getRound();
        String rounds = Lang.get("hotwire.find.round", round, round - 1 + search.countRoundsLeft());
        switch (run.getPhase()) {
            case SWITCHING -> {
                this.title.set(rounds);
                this.body.with(Kit.dim(Lang.get("hotwire.find.switching")));
            }
            case RESTORING -> {
                this.title.set(Lang.get("hotwire.find.restoring"));
                this.body.with(Kit.dim(Lang.get("hotwire.find.switching")));
            }
            case ASKING -> this.showQuestion(run, rounds);
            case DONE -> this.showEnd(run, result);
        }
        this.show();
    }

    private static boolean isNamed(BrokenModSearch.Result result, String id) {
        return switch (result) {
            case BrokenModSearch.Result.Found found -> found.id().equals(id);
            case BrokenModSearch.Result.Tied tied -> tied.ids().contains(id);
            case BrokenModSearch.Result.NotAmong ignored -> false;
        };
    }

    private void showQuestion(BrokenModRun run, String rounds) {
        BrokenModSearch search = run.getSearch();
        int off = search.getOff().size();
        int suspects = search.getSuspects().size();
        int suspectsOff = (int) search.getSuspects().stream().filter(search.getOff()::contains).count();
        this.title.set(rounds);
        String why;
        if (search.isLastCheck()) {
            why = Lang.get("hotwire.find.reason.last");
        } else if (search.getRound() == 1) {
            why = Lang.get("hotwire.find.reason.first", suspectsOff, suspects);
        } else if (search.isFurtherOn()) {
            why = Lang.get("hotwire.find.reason.further", suspects, suspectsOff);
        } else {
            why = Lang.get("hotwire.find.reason.back", suspects, suspects - suspectsOff);
        }
        this.body.with(Kit.dim(why).wrap(), Kit.text(off == 1 ? Lang.get("hotwire.find.question_one")
                : Lang.get("hotwire.find.question", off)).wrap());
        this.buttons.with(Kit.lead(Lang.get("hotwire.find.still"), () -> this.answer(true)),
                Kit.lead(Lang.get("hotwire.find.fixed"), () -> this.answer(false)),
                Kit.push(Lang.get("hotwire.find.unsure"), this::leave), Kit.space(),
                Kit.push(Lang.get("hotwire.find.stop"), this::stop));
    }

    private void answer(boolean stillThere) {
        BrokenModRun run = BrokenModRun.getCurrent();
        if (run != null && !this.game.getProgress().isBusy()) {
            run.answer(stillThere);
            this.showRun();
        }
    }

    private void stop() {
        BrokenModRun run = BrokenModRun.getCurrent();
        if (run != null && !this.game.getProgress().isBusy()) {
            run.stop();
            this.showRun();
        }
    }

    private void showEnd(BrokenModRun run, BrokenModSearch.@Nullable Result result) {
        List<String> unrestored = run.findUnrestored();
        Push close = Kit.push(Lang.get("hotwire.button.close"), this::close);
        if (run.isFailed()) {
            this.setRefused(true);
            this.title.set(Lang.get("hotwire.find.failed.title"));
            this.body.with(Kit.text(Lang.get("hotwire.find.failed.line")).wrap());
            if (!run.getRefusal().isEmpty()) {
                this.body.with(Kit.dim(run.getRefusal()).wrap());
            }
            this.buttons.with(Kit.space(), close);
        } else if (run.isStopped() || result == null) {
            this.title.set(Lang.get("hotwire.find.stopped.title"));
            this.buttons.with(Kit.space(), close);
        } else if (result instanceof BrokenModSearch.Result.NotAmong) {
            this.title.set(Lang.get("hotwire.find.none.title"));
            this.body.with(Kit.text(Lang.get("hotwire.find.none.line", run.getSearch().getOrder().size())).wrap(),
                    Kit.dim(Lang.get("hotwire.find.none.could_be")).wrap());
            this.buttons.with(Kit.space(), close);
        } else {
            this.showNamed(run, result);
        }
        if (!run.getStuck().isEmpty()) {
            this.body.with(Kit.text(Lang.get("hotwire.find.stuck", this.joinNames(List.copyOf(run.getStuck()))),
                    Palette.WARN).wrap());
        }
        if (unrestored.isEmpty()) {
            this.body.with(Kit.dim(Lang.get(run.getLeftOff().isEmpty() ? "hotwire.find.all_back"
                    : "hotwire.find.rest_back")).wrap());
        } else {
            this.body.with(Kit.text(Lang.get("hotwire.find.not_back", this.joinNames(unrestored)), Palette.BAD).wrap());
        }
    }

    private void showNamed(BrokenModRun run, BrokenModSearch.Result result) {
        List<String> off = run.getLeftOff();
        String name;
        if (result instanceof BrokenModSearch.Result.Found found) {
            name = this.catalog.name(found.id());
            this.setMark(ModScreen.Icons.MODS, () -> this.game.findIcon(found.id()));
            this.title.set(Lang.get("hotwire.find.found.title", name));
            this.body.with(Kit.text(Lang.get("hotwire.find.found.line", name)).wrap());
            if (off.size() > 1) {
                List<String> along = off.stream().filter(id -> !id.equals(found.id())).toList();
                this.body.with(Kit.dim(Lang.get("hotwire.find.found.along", name, this.joinNames(along))).wrap());
            }
        } else {
            List<String> tied = result instanceof BrokenModSearch.Result.Tied(List<String> ids) ? ids : List.of();
            name = this.joinNames(tied);
            this.title.set(Lang.get("hotwire.find.tied.title", tied.size()));
            this.body.with(Kit.text(Lang.get("hotwire.find.tied.line", name)).wrap());
        }
        if (run.isNamedOff()) {
            this.body.with(Kit.text(Lang.get(off.size() == 1 ? "hotwire.find.off_now" : "hotwire.find.off_now_many"))
                    .wrap());
        }
        this.body.with(Kit.text(Lang.get("hotwire.find.again"), Palette.FAINT).wrap());
        this.buttons.with(this.setLead(Kit.lead(Lang.get("hotwire.find.leave_off"), this::close)),
                Kit.push(Lang.get("hotwire.find.turn_on"), () -> {
                    this.close();
                    this.game.turnOn(off.stream().filter(id -> !this.catalog.isOn(id)).toList());
                }), Kit.space(), Kit.push(Lang.get("hotwire.find.report"), () -> {
                    this.close();
                    this.screen.showSearchReport(name);
                }));
    }

    private void close() {
        BrokenModRun.end();
        this.dismiss();
    }

    @Override
    public void step(float dt) {
        if (this.open() && this.view == View.OPENING) {
            this.clock = (this.clock + dt) % (BEATS.size() * BEAT);
            int now = (int) (this.clock / BEAT);
            if (now != this.beat) {
                this.showBeat(now);
            }
        }
        BrokenModRun run = BrokenModRun.getCurrent();
        boolean waits = this.open() && this.view == View.ROUND && run != null
                && (run.getPhase() == BrokenModRun.Phase.SWITCHING || run.getPhase() == BrokenModRun.Phase.RESTORING);
        this.idle = waits && !this.game.getProgress().isBusy() ? this.idle + dt : 0;
        if (waits && this.idle > STUCK && !this.stoppable) {
            this.stoppable = true;
            this.buttons.clear().with(Kit.space(), Kit.push(Lang.get("hotwire.find.stop"), this::giveUp));
        }
        super.step(dt);
    }

    private void giveUp() {
        BrokenModRun run = BrokenModRun.getCurrent();
        if (run != null && run.getPhase() == BrokenModRun.Phase.SWITCHING) {
            run.stop();
        } else if (run != null) {
            run.checkSwitch();
        }
        this.showRun();
    }

    @Override
    protected boolean onKey(int key, int modifiers) {
        BrokenModRun run = BrokenModRun.getCurrent();
        if (key != TextField.ESCAPE) {
            return true;
        }
        if (this.view != View.ROUND) {
            this.dismiss();
        } else if (run != null && run.getPhase() == BrokenModRun.Phase.ASKING) {
            this.leave();
        } else if (run != null && run.getPhase() == BrokenModRun.Phase.DONE) {
            this.close();
        }
        return true;
    }
}
