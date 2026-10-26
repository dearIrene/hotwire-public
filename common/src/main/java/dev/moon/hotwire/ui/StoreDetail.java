package dev.moon.hotwire.ui;

import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.modrinth.StoreModel;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public final class StoreDetail extends Panel {
    public interface Adds {
        void add(String projectId, String versionId, String title);
    }

    static final int WIDTH = 520;
    static final int LINE = 12;
    static final int ROW = 18;
    private static final int MOST_VERSIONS = 8;
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern MARKS = Pattern.compile("^[#>*\\-+\\s]+|[*_`~]+");
    private static final Pattern BLANKS = Pattern.compile("\\n{3,}");

    private final StoreModel model;
    private final Adds adds;
    private final EasedValue scroll = EasedValue.ofSpeed(12);
    private ModrinthClient.@Nullable Hit hit;
    private ModrinthClient.@Nullable Project project;
    private List<ModrinthClient.Version> versions = List.of();
    private @Nullable String failure;
    private List<String> bodyLines = List.of();
    private int bodyWidth;
    private float scrollTarget;
    private int contentHeight;
    private int rowsTop;

    public StoreDetail(StoreModel model, Adds adds, Runnable onClose) {
        super(Lang.get("hotwire.button.close"), onClose);
        this.model = model;
        this.adds = adds;
    }

    public void open(ModrinthClient.Hit hit) {
        this.hit = hit;
        this.project = null;
        this.versions = List.of();
        this.failure = null;
        this.bodyLines = List.of();
        this.scrollTarget = 0;
        this.scroll.snap(0);
        this.setPrimaryWidth(110);
        this.placePrimary();
        this.show();
        this.model.findProject(hit.projectId(), found -> this.project = found, why -> this.failure = why);
        this.model.findVersions(hit.projectId(), found -> this.versions = found.size() > MOST_VERSIONS
                ? List.copyOf(found.subList(0, MOST_VERSIONS)) : found, why -> this.failure = why);
    }

    private void placePrimary() {
        ModrinthClient.Hit shown = this.hit;
        if (shown == null) {
            return;
        }
        ModrinthClient.Version update = this.model.getUpdates().get(shown.projectId());
        boolean installed = this.model.isInstalledProject(shown.projectId()) || this.model.getCardsByProject()
                .getOrDefault(shown.projectId(), new StoreModel.Card(shown, false, null)).installed();
        if (update != null) {
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
    }

    @Override
    public void step(float dt) {
        int height = Math.min(this.getHeight() - 48, 440);
        this.size(WIDTH, height);
        int room = this.getBoxHeight() - 46 - 40;
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
        canvas.drawText(canvas.clip(shown.title(), textW - 120), left, y + 16, Colors.fade(Palette.TEXT, alpha));
        String counts = StoreGrid.describeDownloads(page == null ? shown.downloads() : page.downloads()) + " "
                + Lang.get("hotwire.store.downloads");
        canvas.drawText(counts, x + w - 14 - canvas.measureText(counts), y + 16, Colors.fade(Palette.FAINT, alpha));
        int top = y + 46;
        int bottom = y + h - 40;
        canvas.pushClip(x, top, x + w, bottom);
        int lineY = top - Math.round(this.scroll.getValue());
        int startY = lineY;
        String by = shown.author() + (page == null || page.license().isEmpty() ? ""
                : "  " + Lang.get("hotwire.store.license", page.license()));
        canvas.drawText(canvas.clip(by, textW), left, lineY, Colors.fade(Palette.DIM, alpha));
        lineY += LINE + 4;
        if (page != null && !page.sourceUrl().isEmpty()) {
            canvas.drawText(canvas.clip(page.sourceUrl(), textW), left, lineY, Colors.fade(Palette.PATH, alpha));
            lineY += LINE + 4;
        }
        for (String line : this.findBodyLines(canvas, page == null ? shown.description() : page.body(), textW)) {
            canvas.drawText(line, left, lineY, Colors.fade(Palette.TEXT, alpha));
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

    private List<String> findBodyLines(Canvas canvas, String body, int width) {
        if (this.bodyWidth == width && !this.bodyLines.isEmpty()) {
            return this.bodyLines;
        }
        List<String> out = new ArrayList<>();
        String plain = BLANKS.matcher(TAG.matcher(LINK.matcher(IMAGE.matcher(body).replaceAll(""))
                .replaceAll("$1")).replaceAll("")).replaceAll("\n\n");
        for (String paragraph : plain.lines().toList()) {
            String line = MARKS.matcher(paragraph).replaceAll("").strip();
            if (line.isEmpty()) {
                if (!out.isEmpty() && !out.getLast().isEmpty()) {
                    out.add("");
                }
                continue;
            }
            out.addAll(MessageLine.wrap(canvas, line, width, Integer.MAX_VALUE));
        }
        while (!out.isEmpty() && out.getLast().isEmpty()) {
            out.removeLast();
        }
        this.bodyLines = out;
        this.bodyWidth = width;
        return out;
    }

    @Override
    protected boolean onScroll(double amount) {
        this.scrollTarget -= (float) amount * 30;
        return true;
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        ModrinthClient.Hit shown = this.hit;
        int left = this.getBoxX() + 14;
        int right = this.getBoxX() + this.getBoxWidth() - 14;
        if (button == 0 && shown != null && px >= left && px < right) {
            int index = (int) Math.floor((py - this.rowsTop) / ROW);
            int top = this.getBoxY() + 46;
            int bottom = this.getBoxY() + this.getBoxHeight() - 40;
            if (index >= 0 && index < this.versions.size() && py >= top && py < bottom) {
                this.adds.add(shown.projectId(), this.versions.get(index).id(), shown.title());
            }
        }
        return true;
    }
}
