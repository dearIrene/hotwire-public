package dev.moon.hotwire.ui;

import dev.moon.hotwire.modrinth.Downloads;
import dev.moon.hotwire.update.UpdateOffer;
import dev.moon.hotwire.update.UpdateTree;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

final class UpdatesPage {
    private static final int NOTES_TALL = 96;
    private static final int INDENT = 14;
    private static final long READ_NANOS = 100_000_000L;
    private static final double SPEED_SECONDS = 0.5;

    final Part part;
    private final Consumer<UpdateOffer> onUpdate;
    private final Consumer<List<UpdateOffer>> onUpdateAll;
    private final Function<String, Canvas.@Nullable Image> icons;
    private final Supplier<List<Downloads.Job>> jobs;
    private final Runnable onCheck;
    private final Text summary = Kit.dim("");
    private final Push other = Kit.push(Lang.get("hotwire.updates.check"), this::pressOther);
    private final Push main = Kit.lead("", this::pressMain);
    private final Part total = Kit.meter(this::findRunPart);
    private final Line list = Kit.column().gap(2);
    private List<UpdateOffer> offers = List.of();
    private List<UpdateTree.Node> tree = List.of();
    private final Set<String> opened = new HashSet<>();
    private final Set<String> unpicked = new HashSet<>();
    private boolean checked;

    private boolean running;
    private final Map<String, Downloads.Job> seen = new HashMap<>();
    private final Map<String, Text> jobWords = new HashMap<>();
    private Set<String> runFiles = Set.of();
    private long runBytes;
    private long runDone;
    private long lastDone;
    private long readAt;
    private double sinceSpeed;
    private double speed;

    UpdatesPage(Consumer<UpdateOffer> onUpdate, Consumer<List<UpdateOffer>> onUpdateAll,
            Function<String, Canvas.@Nullable Image> icons, Runnable onCheck, Supplier<List<Downloads.Job>> jobs) {
        this.onUpdate = onUpdate;
        this.onUpdateAll = onUpdateAll;
        this.icons = icons;
        this.jobs = jobs;
        this.onCheck = onCheck;
        this.part = Kit.column(Kit.row(Kit.text(Lang.get("hotwire.updates.title")).big(), Kit.space(), this.other,
                this.main).gap(8), this.summary, this.total, Kit.scroll(this.list)).pad(12, 10).gap(8);
        this.fill();
    }

    void setOffers(List<UpdateOffer> offers, boolean checked) {
        this.offers = List.copyOf(offers);
        this.checked = checked;
        this.tree = UpdateTree.of(this.offers);
        Set<String> known = new HashSet<>();
        this.offers.forEach(offer -> known.add(offer.projectId()));
        this.opened.retainAll(known);
        this.unpicked.retainAll(known);
        this.fill();
    }

    void startRun(List<UpdateOffer> chosen) {
        this.running = true;
        this.seen.clear();
        Set<String> files = new HashSet<>();
        chosen.forEach(offer -> files.add(offer.fileName()));
        this.runFiles = files;
        this.runBytes = chosen.stream().mapToLong(UpdateOffer::size).sum();
        this.runDone = 0;
        this.lastDone = 0;
        this.sinceSpeed = 0;
        this.speed = 0;
        this.fill();
    }

    void endRun() {
        this.running = false;
        DownloadNotices.setQuiet(false);
        this.fill();
    }

    private void pressOther() {
        if (!this.running) {
            this.onCheck.run();
        } else if (Downloads.isAnyPaused()) {
            Downloads.resumeAll();
        } else {
            Downloads.pauseAll();
        }
    }

    private void pressMain() {
        List<UpdateOffer> chosen = this.findPicked();
        if (this.running) {
            Downloads.stopAll();
        } else if (chosen.size() == 1) {
            this.onUpdate.accept(chosen.getFirst());
        } else if (!chosen.isEmpty()) {
            this.onUpdateAll.accept(chosen);
        }
    }

    private List<UpdateOffer> findPicked() {
        return this.offers.stream().filter(offer -> !this.unpicked.contains(offer.projectId())).toList();
    }

    private double findRunPart() {
        return this.runBytes <= 0 ? 0 : this.runDone / (double) this.runBytes;
    }

    private double findPart(String file) {
        Downloads.Job job = this.seen.get(file);
        return job == null ? 0 : job.getFraction();
    }

    void refresh(long nowNanos, boolean shown) {
        if (!this.running) {
            return;
        }
        DownloadNotices.setQuiet(shown);
        this.other.label(Lang.get(Downloads.isAnyPaused() ? "hotwire.updates.resume" : "hotwire.updates.pause"));
        if (nowNanos - this.readAt < READ_NANOS) {
            return;
        }
        double read = Math.min(1, (nowNanos - this.readAt) / 1e9);
        this.readAt = nowNanos;
        for (Downloads.Job job : this.jobs.get()) {
            if (this.runFiles.contains(job.name())) {
                this.seen.put(job.name(), job);
            }
        }
        long done = 0;
        for (Downloads.Job job : this.seen.values()) {
            done += job.done();
            Text words = this.jobWords.get(job.name());
            if (words != null) {
                words.set(describe(job));
            }
        }
        this.runDone = done;
        this.sinceSpeed += read;
        if (this.sinceSpeed >= SPEED_SECONDS) {
            double now = (done - this.lastDone) / this.sinceSpeed;
            this.speed = this.speed <= 0 ? now : this.speed * 0.7 + now * 0.3;
            this.lastDone = done;
            this.sinceSpeed = 0;
        }
        this.summary.set(this.describeRun());
    }

    private static String describe(Downloads.Job job) {
        return switch (job.state()) {
            case FAILED -> Lang.get("hotwire.updates.row_failed");
            case PAUSED -> Lang.get("hotwire.updates.row_paused");
            case STOPPED -> Lang.get("hotwire.updates.row_stopped");
            default -> Math.round(job.getFraction() * 100) + "%";
        };
    }

    private String describeRun() {
        long in = this.seen.values().stream().filter(job -> job.state() == Downloads.State.DONE).count();
        String got = Lang.get("hotwire.updates.run", in, this.runFiles.size(),
                CacheFill.describeBytes(this.runDone), CacheFill.describeBytes(this.runBytes));
        if (this.speed <= 0) {
            return got;
        }
        long left = Math.round(Math.max(0, this.runBytes - this.runDone) / this.speed * 1000);
        return Lang.get("hotwire.updates.run_speed", got, CacheFill.describeBytes(Math.round(this.speed)),
                CacheFill.describeMillis(left));
    }

    private void fill() {
        this.list.clear();
        this.jobWords.clear();
        if (this.offers.isEmpty()) {
            this.list.with(Kit.dim(Lang.get(this.checked ? "hotwire.updates.none" : "hotwire.updates.asking"))
                    .wrap());
        }
        this.tree.forEach(node -> this.addRow(node, List.of()));
        List<UpdateOffer> picked = this.findPicked();
        this.total.show(this.running);
        this.other.label(Lang.get(this.running ? "hotwire.updates.pause" : "hotwire.updates.check"));
        boolean all = picked.size() == this.offers.size() && picked.size() > 1;
        String update = all ? Lang.get("hotwire.updates.all") : Lang.get("hotwire.updates.picked", picked.size());
        this.main.label(this.running ? Lang.get("hotwire.updates.stop") : update)
                .active(this.running || !picked.isEmpty()).show(!this.offers.isEmpty());
        this.summary.set(this.running ? this.describeRun() : Lang.get("hotwire.updates.summary", picked.size(),
                CacheFill.describeBytes(picked.stream().mapToLong(UpdateOffer::size).sum())))
                .show(!this.offers.isEmpty());
    }

    private void addRow(UpdateTree.Node node, List<UpdateTree.Node> above) {
        UpdateOffer offer = node.offer();
        String project = offer.projectId();
        Text job = Kit.dim("");
        this.jobWords.put(offer.fileName(), job);
        Line words = Kit.column(Kit.text(offer.name())).gap(2);
        if (node.isLibrary()) {
            words.with(Kit.text(Lang.get("hotwire.updates.needed_by", node.neededBy()), Palette.FAINT));
        }
        words.grow(1);
        Line row = Kit.row(new Part().width(above.size() * INDENT),
                Kit.check(() -> !this.unpicked.contains(project), () -> this.togglePicked(node, above)),
                Kit.picture(() -> this.icons.apply(offer.id()), offer.name(), 16), words, job,
                Kit.text(offer.installedVersion(), Palette.BAD), Kit.dim("→"),
                Kit.text(offer.newVersion(), Palette.GOOD),
                Kit.dim(CacheFill.describeBytes(node.findTotalSize())).spare()).gap(8).pad(4, 3);
        this.list.with(row.press((button, modifiers) -> this.toggleOpen(project)),
                Kit.meter(() -> this.findPart(offer.fileName())).height(1).show(this.running));
        if (!this.opened.contains(project)) {
            return;
        }
        Part notes = offer.changelog().isBlank() ? Kit.dim(Lang.get("hotwire.updates.no_changelog"))
                : Kit.scroll(new Markdown().set(offer.changelog())).capAt(NOTES_TALL);
        this.list.with(Kit.row(new Part().width((above.size() + 1) * INDENT + 30), notes.grow(1)).pad(0, 3));
        List<UpdateTree.Node> under = new ArrayList<>(above);
        under.add(node);
        node.under().forEach(child -> this.addRow(child, under));
    }

    private void toggleOpen(String project) {
        if (!this.opened.remove(project)) {
            this.opened.add(project);
        }
        this.fill();
    }

    private void togglePicked(UpdateTree.Node node, List<UpdateTree.Node> above) {
        if (this.running) {
            return;
        }
        boolean pick = this.unpicked.contains(node.offer().projectId());
        this.setPicked(node, pick);
        if (pick) {
            above.forEach(library -> this.unpicked.remove(library.offer().projectId()));
        }
        this.fill();
    }

    private void setPicked(UpdateTree.Node node, boolean pick) {
        if (pick) {
            this.unpicked.remove(node.offer().projectId());
        } else {
            this.unpicked.add(node.offer().projectId());
        }
        node.under().forEach(child -> this.setPicked(child, pick));
    }
}
