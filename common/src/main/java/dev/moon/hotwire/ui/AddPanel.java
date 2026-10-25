package dev.moon.hotwire.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;

final class AddPanel extends Panel {
    static final int HEED = 0xFFE0A45C;
    private static final int MEASURE = 600;
    private static final int MOST_REASONS = 6;
    private static final int LATE_NAMED = 3;

    private final BiConsumer<Path, AddMode> onConfirm;
    private final Runnable onChange;
    private @Nullable Path jar;
    private CompletableFuture<AddPreview> reading = new CompletableFuture<>();
    private @Nullable AddPreview preview;
    private List<MessageLine.Line> lines = List.of();
    private int widest;

    public AddPanel(BiConsumer<Path, AddMode> onConfirm, Runnable onChange, Runnable onCancel) {
        super(onCancel);
        this.onConfirm = onConfirm;
        this.onChange = onChange;
    }

    void read(Path jar, CompletableFuture<AddPreview> dryRun) {
        this.jar = jar;
        this.reading = dryRun;
        this.preview = null;
        this.lines = List.of();
        this.widest = 0;
        this.setRefused(false);
        this.setPrimary(null, () -> { });
        this.change(this.onChange);
        this.changeActive(false);
        this.show();
    }

    @Nullable AddPreview getPreview() {
        return this.preview;
    }

    @Override
    public void step(float dt) {
        if (this.preview == null && this.reading.isDone()) {
            this.onPreviewReady(this.awaitPreview());
        }
        this.size(Math.max(408, this.widest + 28), 90 + 12 * Math.max(1, this.lines.size()));
        super.step(dt);
    }

    private AddPreview awaitPreview() {
        try {
            return this.reading.join();
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return AddPreview.ofRefusal(this.getFileName(), "That did not work: " + cause);
        }
    }

    private void onPreviewReady(AddPreview preview) {
        this.preview = preview;
        this.setRefused(preview.mode() == AddMode.REFUSED);
        this.changeActive(true);
        String button = preview.mode().findButton();
        Path picked = this.jar;
        if (button != null && picked != null) {
            this.setPrimary(button, () -> this.onConfirm.accept(picked, preview.mode()));
        }
    }

    private String getFileName() {
        Path picked = this.jar;
        Path name = picked == null ? null : picked.getFileName();
        return name == null ? "" : name.toString();
    }

    static String describeHeadsUp(List<String> late) {
        String list = String.join(", ", late.subList(0, Math.min(LATE_NAMED, late.size())));
        if (late.size() > LATE_NAMED) {
            list += ", and " + (late.size() - LATE_NAMED) + " more";
        }
        return "Heads up: a few of its changes only reach what the game sets up from now on, or after a restart: "
                + list + ".";
    }

    private List<MessageLine.Line> layOutBody(Canvas canvas) {
        AddPreview preview = this.preview;
        int wrapAt = Math.min(MEASURE, this.getWidth() - 48 - 28);
        List<MessageLine.Line> out = new ArrayList<>();
        if (preview == null) {
            out.add(new MessageLine.Line("Just a moment.", Palette.DIM));
            return out;
        }
        boolean declined = preview.mode().isDeclinedNow();
        int color = declined ? Palette.TEXT : Palette.DIM;
        for (String line : preview.lines()) {
            MessageLine.addWrapped(canvas, out, line, color, wrapAt);
        }
        if (declined) {
            return out.size() > MOST_REASONS ? new ArrayList<>(out.subList(0, MOST_REASONS)) : out;
        }
        if (!preview.lateReach().isEmpty()) {
            MessageLine.wrap(canvas, describeHeadsUp(preview.lateReach()), wrapAt, Integer.MAX_VALUE)
                    .forEach(text -> out.add(new MessageLine.Line(text, HEED)));
        }
        return out;
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha, int x, int y, int w, int h) {
        this.lines = this.layOutBody(canvas);
        int widest = 0;
        for (MessageLine.Line line : this.lines) {
            widest = Math.max(widest, canvas.measureText(line.text()));
        }
        this.widest = widest;
        AddPreview preview = this.preview;
        String version = preview == null ? "" : preview.version();
        int versionX = x + w - 14 - canvas.measureText(version);
        if (!version.isEmpty()) {
            canvas.drawText(version, versionX, y + 16, Colors.fade(Palette.DIM, alpha));
        }
        Canvas.Image icon = preview == null ? null : preview.icon();
        if (icon != null && this.getShown() > 0.6f) {
            canvas.drawImage(icon, versionX - 6 - 16, y + 12, 16, 16, alpha);
        }
        this.drawHeading(canvas, preview, x + 14, y + 16, versionX - 28 - (x + 14), alpha);
        for (int i = 0; i < this.lines.size(); i++) {
            MessageLine.Line line = this.lines.get(i);
            MessageLine.drawWords(canvas, canvas.clip(line.text(), w - 28), x + 14, y + 46 + 12 * i, line.color(),
                    alpha);
        }
    }

    private void drawHeading(Canvas canvas, @Nullable AddPreview preview, int x, int y, int room, float alpha) {
        String lead = preview == null ? "Reading " : preview.mode().getLead();
        String name = preview == null ? this.getFileName() : preview.name();
        boolean bad = preview != null && preview.mode() == AddMode.REFUSED;
        int leadW = canvas.measureText(lead);
        canvas.drawText(lead, x, y, Colors.fade(Palette.TEXT, alpha));
        canvas.drawText(canvas.clip(name, Math.max(0, room - leadW)), x + leadW, y,
                Colors.fade(bad ? Palette.BAD : Palette.ACCENT, alpha));
    }
}
