package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class MarkdownLines {
    record Line(String text, int indent, int color, int above, String picture) {
        Line(String text, int indent, int color, int above) {
            this(text, indent, color, above, "");
        }
    }

    private static final int PARAGRAPH = 6;
    private static final int HEADING = 10;

    private static final int STEP = 10;

    private static final Pattern SPACING = Pattern.compile("&(?:nbsp|emsp|ensp|thinsp|#8195|#8194|#160);");
    private static final Pattern CODE_POINT = Pattern.compile("&#([0-9]{1,7}|[xX][0-9a-fA-F]{1,6});");
    private static final Pattern BLANK_LINE = Pattern.compile("(?m)^[ \t\u00a0]+$");
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern HTML_HEADING = Pattern.compile("(?is)<h([1-6])[^>]*>(.*?)</h\\1>");
    private static final Pattern HTML_BREAK = Pattern.compile("(?i)<br\\s*/?>|</?p(\\s[^>]*)?>");
    private static final Pattern HTML_ITEM = Pattern.compile("(?i)<li[^>]*>");
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*]\\(\\s*<?([^)\\s>]*)>?[^)]*\\)");
    private static final Pattern HTML_IMAGE = Pattern.compile("(?i)<img\\s[^>]*?src\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>");

    private static final char PICTURE = '\u0001';
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern TAG = Pattern.compile("</?[A-Za-z][^>]*>");
    private static final Pattern TITLE = Pattern.compile("^(#{1,6})\\s+(.*?)[\\s#]*$");
    private static final Pattern UNDERLINE = Pattern.compile("^\\s*(=+|-+)\\s*$");
    private static final Pattern RULE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern TABLE_RULE = Pattern.compile("^[\\s|:-]+$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d+)[.)]\\s+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^\\s*>\\s?(.*)$");
    private static final Pattern MARKS = Pattern.compile("\\*\\*|__|~~|`|(?<!\\w)[*_](?=\\S)|(?<=\\S)[*_](?!\\w)");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private final Canvas canvas;
    private final int width;
    private final List<Line> out = new ArrayList<>();
    private final StringBuilder paragraph = new StringBuilder();
    private int above;

    private MarkdownLines(Canvas canvas, int width) {
        this.canvas = canvas;
        this.width = width;
    }

    static List<Line> of(Canvas canvas, String markdown, int width) {
        MarkdownLines lines = new MarkdownLines(canvas, width);
        lines.read(clean(markdown));
        return List.copyOf(lines.out);
    }

    private static String clean(String markdown) {
        String text = markdown.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ");
        text = COMMENT.matcher(text).replaceAll("");
        text = HTML_HEADING.matcher(text).replaceAll(found -> Matcher.quoteReplacement(
                "\n" + "#".repeat(Integer.parseInt(found.group(1))) + " " + found.group(2) + "\n"));
        text = HTML_BREAK.matcher(text).replaceAll("\n");
        text = HTML_ITEM.matcher(text).replaceAll("\n- ");
        text = HTML_IMAGE.matcher(text).replaceAll(found -> Matcher.quoteReplacement(markPicture(found.group(1))));
        text = IMAGE.matcher(text).replaceAll(found -> Matcher.quoteReplacement(markPicture(found.group(1))));
        text = LINK.matcher(text).replaceAll("$1");
        text = TAG.matcher(text).replaceAll("");
        text = SPACING.matcher(text).replaceAll(" ");
        text = CODE_POINT.matcher(text).replaceAll(found -> Matcher.quoteReplacement(decode(found)));

        text = BLANK_LINE.matcher(text).replaceAll("");
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
    }

    private static String markPicture(String url) {
        return url.startsWith("https://") && !url.toLowerCase(Locale.ROOT).endsWith(".svg")
                ? "\n" + PICTURE + url + "\n" : "";
    }

    private static String decode(MatchResult found) {
        String digits = found.group(1);
        try {
            int code = digits.startsWith("x") || digits.startsWith("X") ? Integer.parseInt(digits.substring(1), 16)
                    : Integer.parseInt(digits);
            return Character.isValidCodePoint(code) ? Character.toString(code) : found.group();
        } catch (NumberFormatException e) {
            return found.group();
        }
    }

    private void read(String text) {
        boolean code = false;
        for (String raw : text.split("\n", -1)) {
            String line = raw.replaceFirst("\\s+$", "");
            if (line.strip().startsWith("```") || line.strip().startsWith("~~~")) {
                this.endParagraph();
                this.addRoom(PARAGRAPH);
                code = !code;
                continue;
            }
            if (code) {
                this.add(line, STEP, Palette.FAINT);
                continue;
            }
            if (line.strip().indexOf(PICTURE) == 0) {
                this.endParagraph();
                this.addRoom(PARAGRAPH);
                this.out.add(new Line("", 0, Palette.TEXT, this.out.isEmpty() ? 0 : this.above,
                        line.strip().substring(1)));
                this.above = 0;
                this.addRoom(PARAGRAPH);
                continue;
            }
            Matcher title = TITLE.matcher(line);
            Matcher bullet = BULLET.matcher(line);
            Matcher numbered = NUMBERED.matcher(line);
            Matcher quote = QUOTE.matcher(line);
            if (line.isBlank()) {
                this.endParagraph();
                this.addRoom(PARAGRAPH);
            } else if (!this.paragraph.isEmpty() && UNDERLINE.matcher(line).matches()) {
                String words = this.paragraph.toString();
                this.paragraph.setLength(0);
                this.addTitle(words, line.strip().startsWith("=") ? 1 : 2);
            } else if (RULE.matcher(line).matches() || TABLE_RULE.matcher(line).matches()) {
                this.endParagraph();
                this.addRoom(PARAGRAPH);
            } else if (title.matches()) {
                this.endParagraph();
                this.addTitle(title.group(2), title.group(1).length());
            } else if (bullet.matches()) {
                this.endParagraph();
                this.wrap(bullet.group(2), findIndent(bullet.group(1)), "• ", Palette.TEXT);
            } else if (numbered.matches()) {
                this.endParagraph();
                this.wrap(numbered.group(3), findIndent(numbered.group(1)), numbered.group(2) + ". ", Palette.TEXT);
            } else if (quote.matches()) {
                this.endParagraph();
                this.wrap(quote.group(1), STEP, "", Palette.DIM);
            } else if (line.strip().startsWith("|")) {
                this.endParagraph();
                this.wrap(String.join(" · ", Stream.of(line.strip().replaceAll("^\\||\\|$", "").split("\\|"))
                        .map(String::strip).toList()), 0, "", Palette.TEXT);
            } else {
                this.paragraph.append(this.paragraph.isEmpty() ? "" : " ").append(line.strip());
            }
        }
        this.endParagraph();
    }

    private void addTitle(String words, int level) {
        this.endParagraph();
        this.addRoom(HEADING);
        this.wrap(words, 0, "", level <= 2 ? Palette.ACCENT : Palette.ON);
        this.addRoom(3);
    }

    private void endParagraph() {
        if (!this.paragraph.isEmpty()) {
            this.wrap(this.paragraph.toString(), 0, "", Palette.TEXT);
            this.paragraph.setLength(0);
        }
    }

    private void wrap(String words, int indent, String prefix, int color) {
        String plain = SPACES.matcher(MARKS.matcher(words).replaceAll("")).replaceAll(" ").strip();
        if (plain.isEmpty()) {
            return;
        }
        int hang = this.canvas.measureText(prefix);
        List<String> parts = MessageLine.wrap(this.canvas, plain, Math.max(40, this.width - indent - hang),
                Integer.MAX_VALUE);
        for (int i = 0; i < parts.size(); i++) {
            this.add(i == 0 ? prefix + parts.get(i) : parts.get(i), i == 0 ? indent : indent + hang, color);
        }
    }

    private void add(String text, int indent, int color) {
        this.out.add(new Line(text, indent, color, this.out.isEmpty() ? 0 : this.above));
        this.above = 0;
    }

    private void addRoom(int room) {
        this.above = Math.max(this.above, room);
    }

    private static int findIndent(String spaces) {
        return Math.min(3, spaces.length() / 2) * STEP;
    }
}
