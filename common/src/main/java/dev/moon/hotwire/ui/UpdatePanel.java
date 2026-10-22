package dev.moon.hotwire.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;

final class UpdatePanel extends Panel {
    private static final int MOST_LINES = 12;
    static final int CARD_FILL = 0x14FFFFFF;
    static final int ICON_PLACEHOLDER = 0x22FFFFFF;
    private static final int WIDTH = 420;
    private static final int CARD_H = 36;

    private final BiConsumer<Path, UpdateMethod> onUpdate;
    private final Runnable onChange;
    private @Nullable Path jar;
    private CompletableFuture<UpdatePreview> reading = new CompletableFuture<>();
    private @Nullable UpdatePreview preview;
    private int lineCount = 1;

    public UpdatePanel(BiConsumer<Path, UpdateMethod> onUpdate, Runnable onChange, Runnable onCancel) {
        super(onCancel);
        this.onUpdate = onUpdate;
        this.onChange = onChange;
    }

    void read(Path jar, CompletableFuture<UpdatePreview> dryRun) {
        this.jar = jar;
        this.reading = dryRun;
        this.preview = null;
        this.lineCount = 1;
        this.setRefused(false);
        this.setPrimary(null, () -> { });
        this.change(this.onChange);
        this.changeActive(false);
        this.show();
    }

    @Nullable UpdatePreview getPreview() {
        return this.preview;
    }

    @Override
    public void step(float dt) {
        if (this.preview == null && this.reading.isDone()) {
            this.onPreviewReady(this.awaitPreview());
        }
        boolean cards = this.preview instanceof UpdatePreview.Ready;
        this.size(WIDTH, 34 + (cards ? 96 : 0) + 12 * this.lineCount + 44);
        super.step(dt);
    }

    private UpdatePreview awaitPreview() {
        try {
            return this.reading.join();
        } catch (RuntimeException e) {
            return new UpdatePreview.Unreadable(this.getFileName());
        }
    }

    private void onPreviewReady(UpdatePreview preview) {
        this.preview = preview;
        this.setRefused(!(preview instanceof UpdatePreview.Ready));
        this.changeActive(true);
        Path picked = this.jar;
        if (preview instanceof UpdatePreview.Ready ready && picked != null) {
            this.setPrimary("Update", () -> this.onUpdate.accept(picked, ready.method()));
        }
    }

    private String getFileName() {
        Path picked = this.jar;
        Path name = picked == null ? null : picked.getFileName();
        return name == null ? "" : name.toString();
    }

    private record Body(String lead, String name, List<String> lines, int color) {
    }

    private Body makeBody() {
        UpdatePreview preview = this.preview;
        if (preview == null) {
            return new Body("Reading ", this.getFileName(), List.of("Just a moment."), Palette.DIM);
        }
        return switch (preview) {
            case UpdatePreview.Ready ready -> new Body("Update ", ready.name(), ready.lines(), Palette.DIM);
            case UpdatePreview.SameVersion same -> new Body("Can't update ", same.name(),
                    List.of(same.name() + " " + same.version() + " is already the version running."), Palette.TEXT);
            case UpdatePreview.Unreadable unreadable -> new Body("Can't update ", unreadable.fileName(),
                    List.of("That jar could not be read."), Palette.TEXT);
            case UpdatePreview.Refused refused -> new Body("Can't update ", refused.name(), List.of(refused.reason()),
                    Palette.TEXT);
        };
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha, int x, int y, int w, int h) {
        Body body = this.makeBody();
        boolean bad = this.preview != null && !(this.preview instanceof UpdatePreview.Ready);
        int leadW = canvas.measureText(body.lead());
        canvas.drawText(body.lead(), x + 14, y + 14, Colors.fade(Palette.TEXT, alpha));
        canvas.drawText(canvas.clip(body.name(), Math.max(0, w - 28 - leadW)), x + 14 + leadW, y + 14,
                Colors.fade(bad ? Palette.BAD : Palette.ACCENT, alpha));
        int linesY = y + 44;
        if (this.preview instanceof UpdatePreview.Ready ready) {
            this.drawCard(canvas, ready, ready.name(), "Installed", ready.installedVersion(), Palette.BAD,
                    x + 14, y + 34, w - 28, alpha);
            int arrowX = x + (w - canvas.measureText("↓")) / 2;
            canvas.drawText("↓", arrowX, y + 34 + CARD_H + 2, Colors.fade(Palette.DIM, alpha));
            this.drawCard(canvas, ready, ready.name(), ready.fileName(), ready.newVersion(), Palette.ON,
                    x + 14, y + 34 + CARD_H + 14, w - 28, alpha);
            linesY = y + 34 + 96;
        }
        List<String> lines = new ArrayList<>();
        for (String line : body.lines()) {
            lines.addAll(MessageLine.wrap(canvas, line, w - 28, Integer.MAX_VALUE));
        }

        if (lines.size() > MOST_LINES) {
            lines = new ArrayList<>(lines.subList(0, MOST_LINES - 1));
            lines.add("More is in the game's log.");
        }
        this.lineCount = Math.max(1, lines.size());
        for (int i = 0; i < lines.size(); i++) {
            canvas.drawText(canvas.clip(lines.get(i), w - 28), x + 14, linesY + 12 * i,
                    Colors.fade(body.color(), alpha));
        }
    }

    private void drawCard(Canvas canvas, UpdatePreview.Ready ready, String name, String source, String version,
            int color, int cx, int cy, int cw, float alpha) {
        canvas.fill(cx, cy, cx + cw, cy + CARD_H, Colors.fade(CARD_FILL, alpha));
        canvas.fill(cx, cy, cx + 2, cy + CARD_H, Colors.fade(color, alpha));
        int iconY = cy + (CARD_H - 24) / 2;
        Canvas.Image icon = ready.icon();
        if (icon == null || this.getShown() <= 0.6f || !canvas.drawImage(icon, cx + 8, iconY, 24, 24, alpha)) {
            canvas.fill(cx + 8, iconY, cx + 32, iconY + 24, Colors.fade(ICON_PLACEHOLDER, alpha));
        }
        int tx = cx + 8 + 24 + 8;
        int versionX = cx + cw - 8 - canvas.measureText(version);
        int room = Math.max(0, versionX - 8 - tx);
        canvas.drawText(canvas.clip(name, room), tx, cy + 8, Colors.fade(Palette.TEXT, alpha));
        canvas.drawText(canvas.clip(source, room), tx, cy + 20, Colors.fade(Palette.DIM, alpha));
        canvas.drawText(version, versionX, cy + (CARD_H - 8) / 2, Colors.fade(color, alpha));
    }
}
