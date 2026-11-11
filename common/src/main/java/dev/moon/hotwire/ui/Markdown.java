package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

public final class Markdown extends Part {
    private static final int LINE = 12;
    private static final int MOST_PICTURE = 240;
    private static final int PICTURE_GAP = 6;

    private final @Nullable StoreIcons pictures;
    private @Nullable Part window;
    private String words = "";
    private List<MarkdownLines.Line> lines = List.of();

    private final Map<String, Integer> rooms = new HashMap<>();

    private final List<LinkBox> links = new ArrayList<>();
    private @Nullable Consumer<String> onLink;

    private record LinkBox(int x, int y, int right, String url) {
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
    protected void measure(Canvas canvas, int room) {
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
        String part = line.text().substring(span.from(), span.to());
        int x = left + canvas.measureText(line.text().substring(0, span.from()));
        boolean link = this.onLink != null && !span.url().isEmpty();
        int color = Colors.fade(link ? Palette.ON : line.color(), alpha);
        if (link) {
            int right = x + canvas.measureText(part);
            canvas.drawText(part, x, y, color);
            canvas.fill(x, y + Text.HEIGHT, right, y + Text.HEIGHT + 1, color);
            this.links.add(new LinkBox(x, y, right, span.url()));
        }
        if (span.bold()) {
            canvas.drawText(part, x + 1, y, color);
        }
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
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

    @Override
    protected boolean takesPointer() {
        return this.onLink != null;
    }
}
