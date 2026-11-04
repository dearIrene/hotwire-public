package dev.moon.hotwire.ui;

import dev.moon.hotwire.modrinth.Downloads;
import dev.moon.hotwire.update.UpdateOffer;
import dev.moon.hotwire.update.UpdateTree;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

final class UpdatesPanel extends Panel {
    static final int WIDTH = 640;
    private static final int LINE = 12;
    private static final int ROW = 22;
    private static final int ICON = 16;
    private static final int BOX = 10;
    private static final int INDENT = 16;
    private static final int ARROW = 12;
    private static final int TOP = 40;
    private static final int FOOT = 40;
    private static final int NOTE_LINES = 3;
    private static final float FADE_SPEED = 5;
    private static final float SPEED_EVERY = 0.5f;
    private static final float READ_EVERY = 0.1f;
    private static final String TICK = "\u2713";

    private static final class Row {
        private final UpdateTree.Node node;
        private final List<UpdateTree.Node> above;
        private int wordsFor = -1;
        private String name = "";
        private String versions = "";
        private String size = "";
        private String needed = "";
        private List<String> notes = List.of();
        private int jobPercent = -1;
        private String jobWords = "";

        private Row(UpdateTree.Node node, List<UpdateTree.Node> above) {
            this.node = node;
            this.above = above;
        }

        private UpdateTree.Node getNode() {
            return this.node;
        }

        private List<UpdateTree.Node> getAbove() {
            return this.above;
        }
    }

    private final Consumer<UpdateOffer> onUpdate;
    private final Consumer<List<UpdateOffer>> onUpdateAll;
    private final Function<String, Canvas.@Nullable Image> icons;
    private final Supplier<List<Downloads.Job>> jobs;
    private final Runnable onCheck;

    private boolean running;
    private final Map<String, Downloads.Job> seen = new HashMap<>();
    private final Map<String, Float> faded = new HashMap<>();
    private Set<String> runFiles = Set.of();
    private long runBytes;
    private long runDone;
    private long lastDone;
    private float sinceSpeed;
    private float sinceRead;
    private String summary = "";
    private boolean summaryStale = true;
    private double speed;
    private List<UpdateOffer> offers = List.of();
    private List<UpdateTree.Node> tree = List.of();
    private final Set<String> opened = new HashSet<>();
    private final Set<String> unpicked = new HashSet<>();
    private List<Row> rows = List.of();
    private List<Integer> heights = List.of();
    private boolean checked;
    private final EasedValue scroll = EasedValue.ofSpeed(12);
    private float scrollTarget;

    @SuppressWarnings("this-escape")
    UpdatesPanel(Consumer<UpdateOffer> onUpdate, Consumer<List<UpdateOffer>> onUpdateAll, Runnable onDone,
            Function<String, Canvas.@Nullable Image> icons, Runnable onCheck, Supplier<List<Downloads.Job>> jobs) {
        super(Lang.get("hotwire.button.done"), onDone);
        this.onUpdate = onUpdate;
        this.onUpdateAll = onUpdateAll;
        this.icons = icons;
        this.jobs = jobs;
        this.onCheck = onCheck;
        this.change(Lang.get("hotwire.updates.check"), onCheck);
    }

    void present() {
        this.scrollTarget = 0;
        this.scroll.snap(0);
        this.show();
    }

    void setOffers(List<UpdateOffer> offers, boolean checked) {
        this.offers = List.copyOf(offers);
        this.checked = checked;
        this.tree = UpdateTree.of(this.offers);
        Set<String> known = new HashSet<>();
        this.offers.forEach(offer -> known.add(offer.projectId()));
        this.opened.retainAll(known);
        this.unpicked.retainAll(known);
        this.listRows();
        this.updatePrimary();
    }

    private void listWithWords() {
        Map<String, Row> known = new HashMap<>();
        this.rows.forEach(row -> known.put(row.getNode().offer().projectId(), row));
        this.listRows();
        List<Row> remaining = new ArrayList<>();
        for (Row row : this.rows) {
            Row was = known.get(row.getNode().offer().projectId());
            remaining.add(was != null && was.getAbove().size() == row.getAbove().size() ? was : row);
        }
        this.rows = remaining;
        this.summaryStale = true;
    }

    void startRun(List<UpdateOffer> chosen) {
        this.running = true;
        this.seen.clear();
        this.faded.clear();
        Set<String> files = new HashSet<>();
        chosen.forEach(offer -> files.add(offer.fileName()));
        this.runFiles = files;
        this.runBytes = chosen.stream().mapToLong(UpdateOffer::size).sum();
        this.runDone = 0;
        this.lastDone = 0;
        this.sinceSpeed = 0;
        this.speed = 0;
        this.change(Lang.get("hotwire.updates.pause"), this::togglePause);
        this.updatePrimary();
    }

    private void togglePause() {
        if (Downloads.isAnyPaused()) {
            Downloads.resumeAll();
        } else {
            Downloads.pauseAll();
        }
    }

    void endRun() {
        this.running = false;
        DownloadNotices.setQuiet(false);
        this.change(Lang.get("hotwire.updates.check"), this.onCheck);
        this.summaryStale = true;
        this.updatePrimary();
    }

    private void listRows() {
        List<Row> listed = new ArrayList<>();
        this.tree.forEach(node -> this.listRow(node, List.of(), listed));
        this.rows = listed;
        this.heights = List.of();
    }

    private void listRow(UpdateTree.Node node, List<UpdateTree.Node> above, List<Row> into) {
        into.add(new Row(node, above));
        if (this.opened.contains(node.offer().projectId())) {
            List<UpdateTree.Node> under = new ArrayList<>(above);
            under.add(node);
            node.under().forEach(child -> this.listRow(child, under, into));
        }
    }

    private void updatePrimary() {
        this.summaryStale = true;
        List<UpdateOffer> picked = this.findPicked();
        boolean any = !this.offers.isEmpty();
        if (this.running) {
            this.setPrimary(Lang.get("hotwire.updates.stop"), Downloads::stopAll);
            this.setPrimaryActive(true);
            return;
        }
        String label = picked.size() == this.offers.size() && picked.size() > 1 ? Lang.get("hotwire.updates.all")
                : Lang.get("hotwire.updates.picked", picked.size());
        this.setPrimary(any ? label : null, () -> {
            List<UpdateOffer> chosen = this.findPicked();
            if (chosen.size() == 1) {
                this.onUpdate.accept(chosen.getFirst());
            } else if (!chosen.isEmpty()) {
                this.onUpdateAll.accept(chosen);
            }
        });
        if (any) {
            this.setPrimaryActive(!picked.isEmpty() && !this.running);
        }
    }

    private List<UpdateOffer> findPicked() {
        return this.offers.stream().filter(offer -> !this.unpicked.contains(offer.projectId())).toList();
    }

    private static List<String> findNotes(Canvas canvas, UpdateOffer offer, int room) {
        List<String> notes = new ArrayList<>();
        offer.changelog().lines().map(String::strip).filter(line -> !line.isEmpty())
                .map(line -> line.replaceAll("^[#*\\-+>\\s]+", "")).limit(NOTE_LINES).forEach(line -> {
                    if (notes.size() < NOTE_LINES) {
                        notes.addAll(MessageLine.wrap(canvas, line, room, NOTE_LINES - notes.size()));
                    }
                });
        return notes;
    }

    private int getRowHeight(int at) {
        return at < this.heights.size() ? this.heights.get(at) : ROW;
    }

    private int getContentHeight() {
        if (this.rows.isEmpty()) {
            return ROW;
        }
        int height = 0;
        for (int at = 0; at < this.rows.size(); at++) {
            height += this.getRowHeight(at);
        }
        return height;
    }

    @Override
    public void step(float dt) {
        this.size(WIDTH, Math.min(this.getHeight() - 48, TOP + this.getContentHeight() + FOOT));
        int room = this.getBoxHeight() - TOP - FOOT;
        this.scrollTarget = Math.max(0, Math.min(this.scrollTarget, Math.max(0, this.getContentHeight() - room)));
        this.scroll.to(this.scrollTarget);
        this.scroll.step(dt);
        if (this.running) {
            DownloadNotices.setQuiet(this.open());
            this.setChangeLabel(Lang.get(Downloads.isAnyPaused() ? "hotwire.updates.resume"
                    : "hotwire.updates.pause"));
            this.followRun(dt);
        }
        super.step(dt);
    }

    private void followRun(float dt) {
        this.sinceRead += dt;
        if (this.sinceRead < READ_EVERY) {
            return;
        }
        float read = this.sinceRead;
        this.sinceRead = 0;
        for (Downloads.Job job : this.jobs.get()) {
            if (this.runFiles.contains(job.name())) {
                this.seen.put(job.name(), job);
            }
        }
        long done = 0;
        for (Downloads.Job job : this.seen.values()) {
            done += job.done();
            if (job.state() != Downloads.State.WAITING) {
                this.faded.merge(job.name(), read * FADE_SPEED, (was, more) -> Math.min(1, was + more));
            }
        }
        this.runDone = done;
        this.summaryStale = true;
        this.sinceSpeed += read;
        if (this.sinceSpeed >= SPEED_EVERY) {
            double now = (done - this.lastDone) / this.sinceSpeed;
            this.speed = this.speed <= 0 ? now : this.speed * 0.7 + now * 0.3;
            this.lastDone = done;
            this.sinceSpeed = 0;
        }
    }

    @Override
    protected boolean onScroll(double amount) {
        this.scrollTarget -= (float) amount * 30;
        return true;
    }

    private OptionalInt findRowAt(double py) {
        int top = this.getBoxY() + TOP;
        if (py < top || py >= top + this.getBoxHeight() - TOP - FOOT) {
            return OptionalInt.empty();
        }
        int rowY = top - Math.round(this.scroll.getValue());
        for (int at = 0; at < this.rows.size(); at++) {
            int height = this.getRowHeight(at);
            if (py >= rowY && py < rowY + height) {
                return OptionalInt.of(at);
            }
            rowY += height;
        }
        return OptionalInt.empty();
    }

    private int findBoxX(Row row) {
        return this.getBoxX() + 14 + row.getAbove().size() * INDENT + ARROW;
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        OptionalInt under = this.findRowAt(py);
        if (under.isEmpty() || px < this.getBoxX() || px >= this.getBoxX() + this.getBoxWidth()) {
            return true;
        }
        int at = under.getAsInt();
        Row row = this.rows.get(at);
        int box = this.findBoxX(row);
        if (px >= box - 3 && px < box + BOX + 3) {
            if (!this.running) {
                this.togglePicked(row);
            }
        } else {
            String project = row.getNode().offer().projectId();
            if (!this.opened.remove(project)) {
                this.opened.add(project);
            }
            this.listWithWords();
        }
        return true;
    }

    private void togglePicked(Row row) {
        boolean pick = this.unpicked.contains(row.getNode().offer().projectId());
        this.setPicked(row.getNode(), pick);
        if (pick) {
            row.getAbove().forEach(library -> this.unpicked.remove(library.offer().projectId()));
        }
        this.updatePrimary();
    }

    private void setPicked(UpdateTree.Node node, boolean pick) {
        if (pick) {
            this.unpicked.remove(node.offer().projectId());
        } else {
            this.unpicked.add(node.offer().projectId());
        }
        node.under().forEach(child -> this.setPicked(child, pick));
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha, int x, int y, int w, int h) {
        canvas.drawText(Lang.get("hotwire.updates.title"), x + 14, y + 16, Colors.fade(Palette.TEXT, alpha));
        int top = y + TOP;
        if (this.offers.isEmpty()) {
            canvas.drawText(Lang.get(this.checked ? "hotwire.updates.none" : "hotwire.updates.asking"), x + 14, top,
                    Colors.fade(Palette.DIM, alpha));
            return;
        }
        if (this.summaryStale) {
            this.summary = this.running ? this.describeRun() : this.describePicked();
            this.summaryStale = false;
        }
        canvas.drawText(this.summary, x + w - 14 - canvas.measureText(this.summary), y + 16,
                Colors.fade(Palette.DIM, alpha));
        canvas.fill(x + 14, top - 6, x + w - 14, top - 5, Colors.fade(Palette.PANEL_EDGE, alpha));
        if (this.running && this.runBytes > 0) {
            int filled = (int) ((w - 28) * Math.min(1, this.runDone / (double) this.runBytes));
            canvas.fill(x + 14, top - 6, x + 14 + filled, top - 5, Colors.fade(Palette.ACCENT, alpha));
        }
        canvas.pushClip(x, top - 4, x + w, y + h - FOOT);
        int rowY = top - Math.round(this.scroll.getValue());
        int hovered = this.isHovered() ? this.findRowAt(this.getPointerY()).orElse(-1) : -1;
        int bottom = y + h - FOOT;
        List<Integer> drawn = new ArrayList<>(this.rows.size());
        for (int at = 0; at < this.rows.size(); at++) {
            Row row = this.rows.get(at);
            this.updateWords(canvas, row, x, w);
            int height = ROW + (row.notes.isEmpty() ? 0 : row.notes.size() * LINE + 4);
            if (rowY + height > top - 4 && rowY < bottom) {
                this.drawRow(canvas, row, at == hovered, alpha, x, rowY, w, height);
            }
            drawn.add(height);
            rowY += height;
        }
        this.heights = drawn;
        canvas.popClip();
    }

    private String describePicked() {
        List<UpdateOffer> picked = this.findPicked();
        return Lang.get("hotwire.updates.summary", picked.size(),
                CacheFill.describeBytes(picked.stream().mapToLong(UpdateOffer::size).sum()));
    }

    private String describeRun() {
        long in = this.seen.values().stream().filter(job -> job.state() == Downloads.State.DONE).count();
        String got = Lang.get("hotwire.updates.run", in, this.runFiles.size(), CacheFill.describeBytes(this.runDone),
                CacheFill.describeBytes(this.runBytes));
        if (this.speed <= 0) {
            return got;
        }
        long left = Math.round(Math.max(0, this.runBytes - this.runDone) / this.speed * 1000);
        return Lang.get("hotwire.updates.run_speed", got, CacheFill.describeBytes(Math.round(this.speed)),
                CacheFill.describeMillis(left));
    }

    private void updateWords(Canvas canvas, Row row, int x, int w) {
        UpdateOffer offer = row.getNode().offer();
        boolean open = this.opened.contains(offer.projectId());
        int forNow = w * 2 + (open ? 1 : 0);
        if (row.wordsFor == forNow) {
            return;
        }
        row.wordsFor = forNow;
        int box = this.findBoxX(row);
        row.notes = open ? findNotes(canvas, offer, w - (box - x) - BOX - 8 - 14) : List.of();
        row.size = CacheFill.describeBytes(offer.size());
        row.needed = row.getNode().isLibrary() ? Lang.get("hotwire.updates.needed_by", row.getNode().neededBy()) : "";
        int restEnd = x + w - 14 - canvas.measureText(row.size) - 10;
        if (!row.needed.isEmpty()) {
            restEnd -= canvas.measureText(row.needed) + 10;
        }
        int textX = box + BOX + 6 + ICON + 6;
        row.name = canvas.clip(offer.name(), restEnd - textX);
        int after = textX + canvas.measureText(row.name) + 8;
        row.versions = canvas.clip(offer.installedVersion() + " to " + offer.newVersion(),
                Math.max(0, restEnd - after));
    }

    private void drawRow(Canvas canvas, Row row, boolean hovered, float alpha, int x, int rowY, int w, int height) {
        UpdateOffer offer = row.getNode().offer();
        boolean open = this.opened.contains(offer.projectId());
        boolean picked = !this.unpicked.contains(offer.projectId());
        int textColor = picked ? Palette.TEXT : Palette.FAINT;
        int box = this.findBoxX(row);
        int textY = rowY + (ROW - LINE) / 2 + 1;
        if (hovered) {
            canvas.fill(x + 8, rowY, x + w - 8, rowY + height, Colors.fade(Palette.HAIRLINE, alpha));
        }
        if (!row.getNode().under().isEmpty()) {
            canvas.drawText(open ? "v" : ">", box - ARROW, textY, Colors.fade(Palette.ACCENT, alpha));
        }
        int boxY = rowY + (ROW - BOX) / 2;
        canvas.fill(box, boxY, box + BOX, boxY + BOX, Colors.fade(picked ? Palette.ACCENT : Palette.OFF, alpha));
        canvas.fill(box + 1, boxY + 1, box + BOX - 1, boxY + BOX - 1, Colors.fade(Palette.PANEL, alpha));
        if (picked) {
            canvas.fill(box + 3, boxY + 3, box + BOX - 3, boxY + BOX - 3, Colors.fade(Palette.ACCENT, alpha));
        }
        int iconX = box + BOX + 6;
        int iconY = rowY + (ROW - ICON) / 2;
        Canvas.Image icon = this.icons.apply(offer.id());
        if (icon == null || !canvas.drawImage(icon, iconX, iconY, ICON, ICON, alpha)) {
            canvas.fill(iconX, iconY, iconX + ICON, iconY + ICON, Colors.fade(Palette.DIM & 0x40FFFFFF, alpha));
        }
        Downloads.Job job = this.running ? this.seen.get(offer.fileName()) : null;
        float progressed = job == null ? 0 : this.faded.getOrDefault(offer.fileName(), 0f);
        int sizeX = x + w - 14 - canvas.measureText(row.size);
        canvas.drawText(row.size, sizeX, textY, Colors.fade(Palette.DIM, alpha));

        if (!row.needed.isEmpty()) {
            canvas.drawText(row.needed, sizeX - 10 - canvas.measureText(row.needed), textY,
                    Colors.fade(Palette.ACCENT_SOFT, alpha));
        }
        int textX = iconX + ICON + 6;
        canvas.drawText(row.name, textX, textY, Colors.fade(textColor, alpha));
        int after = textX + canvas.measureText(row.name) + 8;
        if (progressed < 1) {
            canvas.drawText(row.versions, after, textY, Colors.fade(Palette.DIM, alpha * (1 - progressed)));
        }
        if (job != null && progressed > 0) {
            int percent = job.state() == Downloads.State.DONE ? -2 : job.state() == Downloads.State.FAILED ? -3
                    : job.state() == Downloads.State.PAUSED ? -4 : job.state() == Downloads.State.STOPPED ? -5
                    : (int) (job.getFraction() * 100);
            if (row.jobPercent != percent) {
                row.jobPercent = percent;
                row.jobWords = describeJob(job);
            }
            canvas.drawText(row.jobWords, after, textY, Colors.fade(job.state() == Downloads.State.FAILED
                    ? Palette.BAD : job.state() == Downloads.State.DONE ? Palette.ON : Palette.TEXT,
                    alpha * progressed));
        }
        if (job != null) {
            int lineY = rowY + ROW - 2;
            int end = x + w - 14;
            canvas.fill(textX, lineY, end, lineY + 1, Colors.fade(Palette.TRACK, alpha));
            int filled = textX + (int) ((end - textX) * job.getFraction());
            canvas.fill(textX, lineY, filled, lineY + 1, Colors.fade(job.state() == Downloads.State.DONE ? Palette.ON
                    : job.state() == Downloads.State.FAILED ? Palette.BAD : Palette.ACCENT, alpha));
        }
        int noteY = rowY + ROW;
        for (String note : row.notes) {
            canvas.drawText(note, box + BOX + 6, noteY, Colors.fade(Palette.FAINT, alpha));
            noteY += LINE;
        }
    }

    private static String describeJob(Downloads.Job job) {
        return switch (job.state()) {
            case DONE -> TICK;
            case FAILED -> Lang.get("hotwire.updates.row_failed");
            case PAUSED -> Lang.get("hotwire.updates.row_paused");
            case STOPPED -> Lang.get("hotwire.updates.row_stopped");
            case WAITING, GETTING -> Math.round(job.getFraction() * 100) + "%  " + CacheFill.describeBytes(job.done());
        };
    }
}
