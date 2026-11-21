package dev.moon.hotwire.ui.widgets;

import dev.moon.hotwire.ui.Lang;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

public final class Markdown extends Part {
    private static final int LINE = 12;
    private static final int MOST_PICTURE = 240;
    private static final int PICTURE_GAP = 6;

    private static final List<String> MODRINTH_PICTURES = List.of("https://cdn.modrinth.com/",
            "https://cdn-raw.modrinth.com/");

    private final @Nullable StoreIcons pictures;
    private @Nullable Part window;
    private String words = "";
    private List<MarkdownLines.Line> lines = List.of();

    private final Map<String, Integer> rooms = new HashMap<>();

    private final List<LinkBox> links = new ArrayList<>();
    private @Nullable Consumer<String> onLink;

    private final Set<String> allowed = new HashSet<>();
    private final List<WaitingBox> waiting = new ArrayList<>();

    private record LinkBox(int x, int y, int right, String url) {
    }

    private record WaitingBox(int y, String urls) {
    }

    public Markdown() {
        this(null);
    }

    public Markdown(@Nullable StoreIcons pictures) {
        this.pictures = pictures;
    }

    public Markdown within(Part window) {
        this.window = window;
        return this;
    }

    public Markdown onLink(Consumer<String> opener) {
        this.onLink = opener;
        return this;
    }

    public Markdown set(String words) {
        if (!words.equals(this.words)) {
            this.words = words;
            this.rooms.clear();
            markChanged();
        }
        return this;
    }

    private int findRoom(MarkdownLines.Line line) {
        return line.picture().isEmpty() ? LINE : this.rooms.getOrDefault(line.picture(), 0);
    }

    @Override
    public void measure(Canvas canvas, int room) {
        this.lines = this.pictures == null ? MarkdownLines.ofChangelog(canvas, this.words, room)
                : MarkdownLines.of(canvas, this.words, room);
        this.hugWidth = room;
        this.hugHeight = this.lines.stream().mapToInt(line -> line.above() + this.findRoom(line)).sum();
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        int x = this.getX();
        int y = this.getY();
        this.links.clear();
        this.waiting.clear();
        for (MarkdownLines.Line line : this.lines) {
            y += line.above();
            Part in = this.window;
            if (!line.picture().isEmpty() && in != null && (y > in.getY() + in.getHeight() + 2 * MOST_PICTURE
                    || y < in.getY() - 2 * MOST_PICTURE)) {
                y += this.findRoom(line);
                continue;
            }
            if (!line.picture().isEmpty()) {
                this.paintPictures(canvas, line.picture(), x, y, alpha);
            } else if (line.columns().isEmpty()) {
                canvas.drawText(line.text(), x + line.indent(), y, Colors.fade(line.color(), alpha));
                for (MarkdownLines.Span span : line.spans()) {
                    this.paintSpan(canvas, line, span, x + line.indent(), y, alpha);
                }
            } else {
                String[] cells = line.text().split("\t", -1);
                List<Integer> columns = line.columns();
                for (int at = 0; at < cells.length && at < columns.size(); at++) {
                    int end = at + 1 < columns.size() ? columns.get(at + 1) : this.getWidth();
                    canvas.drawText(canvas.clip(cells[at], end - columns.get(at) - 6),
                            x + line.indent() + columns.get(at), y, Colors.fade(line.color(), alpha));
                }
            }
            y += this.findRoom(line);
        }
    }

    private void paintSpan(Canvas canvas, MarkdownLines.Line line, MarkdownLines.Span span, int left, int y,
            float alpha) {
        if (this.onLink == null || span.url().isEmpty()) {
            return;
        }
        String part = line.text().substring(span.from(), span.to());
        int x = left + canvas.measureText(line.text().substring(0, span.from()));
        int right = x + canvas.measureText(part);
        int color = Colors.fade(Palette.ON, alpha);
        canvas.drawText(part, x, y, color);
        canvas.fill(x, y + Text.HEIGHT, right, y + Text.HEIGHT + 1, color);
        this.links.add(new LinkBox(x, y, right, span.url()));
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        for (WaitingBox box : this.waiting) {
            if (button == 0 && py >= box.y() && py < box.y() + LINE) {
                this.allowed.add(box.urls());
                this.rooms.remove(box.urls());
                markChanged();
                return true;
            }
        }
        Consumer<String> opener = this.onLink;
        for (LinkBox box : this.links) {
            if (opener != null && button == 0 && px >= box.x() && px < box.right() && py >= box.y()
                    && py < box.y() + LINE) {
                opener.accept(box.url());
                return true;
            }
        }
        return false;
    }

    private void paintPictures(Canvas canvas, String urls, int left, int y, float alpha) {
        if (!this.allowed.contains(urls)) {
            for (String url : urls.split(" ", -1)) {
                if (MODRINTH_PICTURES.stream().noneMatch(url::startsWith)) {
                    this.paintWaiting(canvas, urls, url, left, y, alpha);
                    return;
                }
            }
        }
        List<StoreIcons.PagePicture> found = new ArrayList<>();
        for (String url : urls.split(" ", -1)) {
            StoreIcons.PagePicture picture = this.pictures == null ? null : this.pictures.findPicture(url);
            if (picture != null) {
                found.add(picture);
            }
        }
        if (found.isEmpty()) {
            return;
        }
        int width = this.getWidth();
        int gaps = PICTURE_GAP * (found.size() - 1);
        float height = Math.min(MOST_PICTURE, found.stream().mapToInt(StoreIcons.PagePicture::height).max().orElse(1));
        float wide = (float) found.stream().mapToDouble(one -> (double) one.width() / one.height()).sum() * height;
        if (wide > width - gaps) {
            height = height * (width - gaps) / wide;
            wide = width - gaps;
        }
        int tall = Math.max(1, Math.round(height));

        if (this.rooms.getOrDefault(urls, 0) != tall + 4) {
            this.rooms.put(urls, tall + 4);
            markChanged();
            return;
        }
        int x = left + Math.round((width - wide - gaps) / 2);
        for (StoreIcons.PagePicture picture : found) {
            int shown = Math.max(1, Math.round((float) picture.width() * tall / picture.height()));
            canvas.drawImage(picture.image(), x, y, shown, tall, alpha);
            x += shown + PICTURE_GAP;
        }
    }

    private void paintWaiting(Canvas canvas, String urls, String url, int left, int y, float alpha) {
        if (this.rooms.getOrDefault(urls, 0) != LINE) {
            this.rooms.put(urls, LINE);
            markChanged();
            return;
        }
        String said = Lang.get("hotwire.store.picture_elsewhere", findSite(url));
        canvas.drawText(canvas.clip(said, this.getWidth()), left, y, Colors.fade(Palette.DIM, alpha));
        this.waiting.add(new WaitingBox(y, urls));
    }

    private static String findSite(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? url : host;
        } catch (URISyntaxException e) {
            return url;
        }
    }

    @Override
    protected boolean takesPointer() {
        return this.onLink != null || this.pictures != null;
    }
}
