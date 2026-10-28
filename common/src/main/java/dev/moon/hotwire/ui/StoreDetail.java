package dev.moon.hotwire.ui;

import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.modrinth.StoreModel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

public final class StoreDetail extends Panel {
    public interface Adds {
        void add(String projectId, String versionId, String title);

        void queueForNextStart(String projectId, String versionId, String title);
    }

    static final int WIDTH = 520;
    static final int LINE = 12;
    static final int ROW = 18;
    private static final int ICON = 28;
    private static final int MOST_VERSIONS = 8;
    private static final int LEAST_HEIGHT = 180;
    private static final int MOST_HEIGHT = 440;

    private static final int HEAD = 50;
    private static final int MOST_PICTURE = 240;

    private final StoreModel model;
    private final StoreIcons icons;
    private final Adds adds;
    private final Consumer<String> openLink;
    private final EasedValue scroll = EasedValue.ofSpeed(12);
    private ModrinthClient.@Nullable Hit hit;
    private ModrinthClient.@Nullable Project project;
    private List<ModrinthClient.Version> versions = List.of();
    private @Nullable String failure;
    private List<MarkdownLines.Line> bodyLines = List.of();

    private String bodyRead = "";
    private int bodyWidth;
    private float scrollTarget;
    private int contentHeight;
    private int rowsTop;

    private int linkY = Integer.MIN_VALUE;
    private int linkWidth;

    private final Map<String, Integer> pictureRooms = new HashMap<>();

    public StoreDetail(StoreModel model, StoreIcons icons, Adds adds, Consumer<String> openLink, Runnable onClose) {
        super(Lang.get("hotwire.button.close"), onClose);
        this.model = model;
        this.icons = icons;
        this.adds = adds;
        this.openLink = openLink;
        this.setLineY(HEAD - 10);
    }

    public void open(ModrinthClient.Hit hit) {
        this.hit = hit;
        this.project = null;
        this.versions = List.of();
        this.failure = null;
        this.bodyLines = List.of();
        this.bodyRead = "";
        this.pictureRooms.clear();
        this.contentHeight = 0;
        this.scrollTarget = 0;
        this.scroll.snap(0);
        this.setPrimaryWidth(110);
        this.placePrimary();
        this.show();
        this.model.findProject(hit, found -> this.project = found, why -> this.failure = why);
        this.model.findVersions(hit, found -> this.versions = found.size() > MOST_VERSIONS
                ? List.copyOf(found.subList(0, MOST_VERSIONS)) : found, why -> this.failure = why);
    }

    private void placePrimary() {
        ModrinthClient.Hit shown = this.hit;
        if (shown == null) {
            return;
        }
        if (this.model.isShowingPacks()) {
            this.placePackPrimary(shown);
            return;
        }
        ModrinthClient.Version update = this.model.getUpdates().get(shown.projectId());
        boolean installed = this.model.isInstalledProject(shown.projectId()) || this.model.getCardsByProject()
                .getOrDefault(shown.projectId(), new StoreModel.Card(shown, false, null)).installed();
        if (this.model.isQueuedProject(shown.projectId())) {
            this.setPrimary(Lang.get("hotwire.store.queued"), () -> { });
            this.setPrimaryActive(false);
        } else if (update != null) {
            this.setPrimary(Lang.get("hotwire.store.update"), () -> this.adds.add(shown.projectId(), update.id(),
                    shown.title()));
            this.setPrimaryActive(true);
        } else if (installed) {
            this.setPrimary(Lang.get("hotwire.store.installed"), () -> { });
            this.setPrimaryActive(false);
        } else {
            this.setPrimary(Lang.get("hotwire.store.add"), () -> this.adds.add(shown.projectId(), "",
                    shown.title()));
            this.setPrimaryActive(true);
        }
        this.change(Lang.get("hotwire.store.download"), installed || this.model.isQueuedProject(shown.projectId())
                ? null : () -> this.adds.queueForNextStart(shown.projectId(), update == null ? "" : update.id(),
                        shown.title()));
    }

    private void placePackPrimary(ModrinthClient.Hit shown) {
        StoreModel.PackState state = this.model.findPackState(shown.projectId());
        switch (state) {
            case MISSING -> {
                this.setPrimary(Lang.get("hotwire.store.download"), () -> this.adds.add(shown.projectId(), "",
                        shown.title()));
                this.setPrimaryActive(true);
            }
            case ON, OFF -> {
                this.setPrimary(Lang.get(state == StoreModel.PackState.ON ? "hotwire.store.pack.turn_off"
                        : "hotwire.store.pack.turn_on"), () -> this.model.togglePack(shown.projectId()));
                this.setPrimaryActive(true);
            }
            case DOWNLOADING, NEEDS_WORLD -> {
                this.setPrimary(Lang.get(state == StoreModel.PackState.DOWNLOADING ? "hotwire.store.pack.downloading"
                        : "hotwire.store.pack.needs_world"), () -> { });
                this.setPrimaryActive(false);
            }
        }
        this.change(Lang.get("hotwire.store.download"), null);
    }

    @Override
    public void step(float dt) {
        int most = Math.min(this.getHeight() - 48, MOST_HEIGHT);
        this.size(WIDTH, Math.min(most, Math.max(LEAST_HEIGHT, HEAD + this.contentHeight + 46)));
        int room = this.getBoxHeight() - HEAD - 40;
        this.scrollTarget = Math.max(0, Math.min(this.scrollTarget, Math.max(0, this.contentHeight - room)));
        this.scroll.to(this.scrollTarget);
        this.scroll.step(dt);
        super.step(dt);
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha, int x, int y, int w, int h) {
        ModrinthClient.Hit shown = this.hit;
        if (shown == null) {
            return;
        }
        ModrinthClient.Project page = this.project;
        int left = x + 14;
        int textW = w - 28;
        this.drawHeader(canvas, alpha, shown, page, left, y + 10, x + w - 14);
        int top = y + HEAD;
        int bottom = y + h - 40;
        canvas.pushClip(x, top, x + w, bottom);
        int lineY = top + 4 - Math.round(this.scroll.getValue());
        int startY = lineY;
        this.linkY = Integer.MIN_VALUE;
        if (page != null && !page.sourceUrl().isEmpty()) {
            String link = canvas.clip(page.sourceUrl(), textW);
            this.linkWidth = canvas.measureText(link);
            this.linkY = lineY;
            boolean over = this.isHovered() && this.isOnLink(this.getPointerX(), this.getPointerY(), left)
                    && lineY >= top && lineY + LINE <= bottom;
            canvas.drawText(link, left, lineY, Colors.fade(over ? Palette.TEXT : Palette.PATH, alpha));
            if (over) {
                canvas.fill(left, lineY + 9, left + this.linkWidth, lineY + 10, Colors.fade(Palette.PATH, alpha));
            }
            lineY += LINE + 6;
        }
        for (MarkdownLines.Line line : this.findBodyLines(canvas, page == null ? shown.description() : page.body(),
                textW)) {
            lineY += line.above();
            if (!line.picture().isEmpty()) {
                lineY += this.drawPicture(canvas, alpha, line.picture(), left, lineY, textW, top, bottom);
                continue;
            }
            canvas.drawText(line.text(), left + line.indent(), lineY, Colors.fade(line.color(), alpha));
            lineY += LINE;
        }
        lineY += 6;
        canvas.fill(left, lineY, x + w - 14, lineY + 1, Colors.fade(Palette.TRACK, alpha));
        lineY += 8;
        canvas.drawText(Lang.get("hotwire.store.versions"), left, lineY, Colors.fade(Palette.DIM, alpha));
        lineY += LINE + 2;
        this.rowsTop = lineY;
        if (this.versions.isEmpty()) {
            canvas.drawText(this.failure == null ? Lang.get("hotwire.store.loading")
                    : Lang.get("hotwire.store.failed", this.failure), left, lineY,
                    Colors.fade(this.failure == null ? Palette.FAINT : Palette.BAD, alpha));
            lineY += ROW;
        }
        for (ModrinthClient.Version version : this.versions) {
            boolean over = this.isHovered() && this.getPointerY() >= lineY && this.getPointerY() < lineY + ROW
                    && this.getPointerX() >= left && this.getPointerX() < x + w - 14 && lineY >= top
                    && lineY + ROW <= bottom;
            if (over) {
                canvas.fill(left, lineY - 2, x + w - 14, lineY + ROW - 2, Colors.fade(Palette.HAIRLINE, alpha));
            }
            String name = version.number() + (version.name().isEmpty() || version.name().equals(version.number())
                    ? "" : "  " + version.name());
            canvas.drawText(canvas.clip(name, textW - 80), left + 4, lineY + 2,
                    Colors.fade(over ? Palette.TEXT : Palette.DIM, alpha));
            String needs = version.requiredProjects().isEmpty() ? ""
                    : Lang.get("hotwire.store.needs", version.requiredProjects().size());
            String right = over ? Lang.get("hotwire.store.add_this") : needs;
            canvas.drawText(right, x + w - 18 - canvas.measureText(right), lineY + 2,
                    Colors.fade(over ? Palette.ACCENT : Palette.FAINT, alpha));
            lineY += ROW;
        }
        this.contentHeight = lineY - startY;
        canvas.popClip();
    }

    private void drawHeader(Canvas canvas, float alpha, ModrinthClient.Hit shown,
            ModrinthClient.@Nullable Project page, int left, int top, int right) {
        Canvas.Image icon = shown.iconUrl().isEmpty() ? null : this.icons.find(shown.projectId(), shown.iconUrl());
        if (icon == null || !canvas.drawImage(icon, left, top, ICON, ICON, alpha)) {
            canvas.fill(left, top, left + ICON, top + ICON, Colors.fade(Palette.TRACK, alpha));
        }
        String counts = StoreGrid.describeDownloads(page == null ? shown.downloads() : page.downloads()) + " "
                + Lang.get("hotwire.store.downloads");
        int countsW = canvas.measureText(counts);
        canvas.drawText(counts, right - countsW, top + 3, Colors.fade(Palette.FAINT, alpha));
        int textX = left + ICON + 10;
        canvas.drawText(canvas.clip(shown.title(), right - textX - countsW - 12), textX, top + 3,
                Colors.fade(Palette.TEXT, alpha));
        String by = shown.author() + (page == null || page.license().isEmpty() ? ""
                : "  " + Lang.get("hotwire.store.license", page.license()));
        canvas.drawText(canvas.clip(by, right - textX), textX, top + 16, Colors.fade(Palette.DIM, alpha));
    }

    private int drawPicture(Canvas canvas, float alpha, String url, int left, int y, int width, int top, int bottom) {
        if (y < top - MOST_HEIGHT || y > bottom + MOST_HEIGHT) {
            return this.pictureRooms.getOrDefault(url, 0);
        }
        StoreIcons.PagePicture picture = this.icons.findPicture(url);
        if (picture == null) {
            return this.pictureRooms.getOrDefault(url, 0);
        }
        int wide = Math.min(width, picture.width());
        int height = Math.max(1, Math.min(MOST_PICTURE, Math.round((float) picture.height() * wide / picture.width())));
        int shown = Math.max(1, Math.round((float) picture.width() * height / picture.height()));
        canvas.drawImage(picture.image(), left + (width - shown) / 2, y, shown, height, alpha);
        this.pictureRooms.put(url, height + 4);
        return height + 4;
    }

    private boolean isOnLink(double px, double py, int left) {
        return this.linkY != Integer.MIN_VALUE && px >= left && px < left + this.linkWidth && py >= this.linkY - 2
                && py < this.linkY + LINE - 2;
    }

    private List<MarkdownLines.Line> findBodyLines(Canvas canvas, String body, int width) {
        if (this.bodyWidth != width || !this.bodyRead.equals(body)) {
            this.bodyLines = MarkdownLines.of(canvas, body, width);
            this.bodyRead = body;
            this.bodyWidth = width;
        }
        return this.bodyLines;
    }

    @Override
    protected boolean onScroll(double amount) {
        this.scrollTarget -= (float) amount * 30;
        return true;
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        ModrinthClient.Hit shown = this.hit;
        ModrinthClient.Project page = this.project;
        int left = this.getBoxX() + 14;
        int right = this.getBoxX() + this.getBoxWidth() - 14;
        int top = this.getBoxY() + HEAD;
        int bottom = this.getBoxY() + this.getBoxHeight() - 40;
        if (button != 0 || shown == null || px < left || px >= right || py < top || py >= bottom) {
            return true;
        }
        if (page != null && this.isOnLink(px, py, left)) {
            this.openLink.accept(page.sourceUrl());
            return true;
        }
        int index = (int) Math.floor((py - this.rowsTop) / ROW);
        if (index >= 0 && index < this.versions.size()) {
            this.adds.add(shown.projectId(), this.versions.get(index).id(), shown.title());
        }
        return true;
    }
}
