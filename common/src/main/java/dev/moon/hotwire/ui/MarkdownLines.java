package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class MarkdownLines {
    record Line(String text, int indent, int color, int above, String picture, List<Integer> columns,
            List<Span> spans) {
        Line(String text, int indent, int color, int above, String picture, List<Integer> columns) {
            this(text, indent, color, above, picture, columns, List.of());
        }

        Line(String text, int indent, int color, int above, String picture) {
            this(text, indent, color, above, picture, List.of());
        }

        Line(String text, int indent, int color, int above) {
            this(text, indent, color, above, "");
        }
    }

    record Span(int from, int to, boolean bold, String url) {
    }

    private record Word(String text, boolean bold, String url) {
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
    private static final char PICTURE_END = '\u0002';
    private static final Pattern PICTURE_ROW = Pattern.compile(
            "\u0001[^\u0002]*\u0002(?:[ \t]*\n?[ \t]*\u0001[^\u0002]*\u0002)*");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\(\\s*<?([^)\\s>]*)>?[^)]*\\)");

    private static final char LINK_START = '\u0003';
    private static final char LINK_URL = '\u0004';
    private static final char LINK_END = '\u0005';
    private static final char BOLD = '\u0006';
    private static final Pattern LINK_MARKS = Pattern.compile("\u0003|\u0004[^\u0005]*\u0005");
    private static final Pattern LINK_ADDRESS = Pattern.compile("\u0004([^\u0005]*)\u0005");
    private static final Pattern BOLD_MARK = Pattern.compile("\\*\\*|__");

    private static final Pattern REFERENCE = Pattern.compile("(?m)^ {0,3}\\[([^\\]]+)]:\\s*<?([^\\s>]+)>?.*$\\n?");
    private static final Pattern REFERENCE_IMAGE = Pattern.compile("!\\[([^\\]]*)](?:\\[([^\\]]*)])?");
    private static final Pattern REFERENCE_LINK = Pattern.compile("\\[([^\\]]*)]\\[([^\\]]*)]");

    private static final Pattern HARD_BREAK = Pattern.compile("\\\\\n");
    private static final Pattern ESCAPE = Pattern.compile("\\\\([\\\\`*_{}\\[\\]()#+\\-.!|>~])");

    private static final Pattern EMPTY_TITLE = Pattern.compile("^\\s*#{1,6}\\s*$");
    private static final Pattern TAG = Pattern.compile("</?[A-Za-z][^>]*>");
    private static final Pattern TITLE = Pattern.compile("^(#{1,6})\\s+(.*?)[\\s#]*$");
    private static final Pattern UNDERLINE = Pattern.compile("^\\s*(=+|-+)\\s*$");
    private static final Pattern RULE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern TABLE_RULE = Pattern.compile("^[\\s|:-]+$");

    private static final Pattern BOX_RULE = Pattern.compile("^[\\s|+=:\\-\u2500-\u257f]+$");
    private static final Pattern CELL_BAR = Pattern.compile("[|\u2502\u2503\u2551]");
    private static final int CELL_GAP = 12;
    private static final int NARROWEST_CELL = 24;
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d+)[.)]\\s+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^\\s*>\\s?(.*)$");
    private static final Pattern MARKS = Pattern.compile("\\*\\*|__|~~|`|(?<!\\w)[*_](?=\\S)|(?<=\\S)[*_](?!\\w)");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private final Canvas canvas;
    private final int width;
    private final int plain;
    private final int strong;
    private final List<Line> out = new ArrayList<>();
    private final StringBuilder paragraph = new StringBuilder();
    private final List<List<String>> table = new ArrayList<>();
    private int above;

    private MarkdownLines(Canvas canvas, int width, int plain, int strong) {
        this.canvas = canvas;
        this.width = width;
        this.plain = plain;
        this.strong = strong;
    }

    static List<Line> of(Canvas canvas, String markdown, int width) {
        return readAll(new MarkdownLines(canvas, width, Palette.TEXT, Palette.TEXT), markdown);
    }

    static List<Line> ofChangelog(Canvas canvas, String markdown, int width) {
        return readAll(new MarkdownLines(canvas, width, Colors.mix(Palette.DIM, Palette.TEXT, 0.45f), Palette.TEXT),
                markdown);
    }

    private static List<Line> readAll(MarkdownLines lines, String markdown) {
        lines.read(clean(markdown));
        return List.copyOf(lines.out);
    }

    private int findColor(String words) {
        return words.startsWith("**") || words.startsWith("__") ? this.strong : this.plain;
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
        Map<String, String> references = new HashMap<>();
        Matcher defined = REFERENCE.matcher(text);
        while (defined.find()) {
            references.putIfAbsent(defined.group(1).toLowerCase(Locale.ROOT), defined.group(2));
        }
        text = REFERENCE.matcher(text).replaceAll("");
        text = REFERENCE_IMAGE.matcher(text).replaceAll(found -> {
            String name = found.group(2) == null || found.group(2).isEmpty() ? found.group(1) : found.group(2);
            String url = references.get(name.toLowerCase(Locale.ROOT));
            return Matcher.quoteReplacement(url == null ? found.group(1) : markPicture(url));
        });
        text = LINK.matcher(text).replaceAll(found -> Matcher.quoteReplacement(markLink(found.group(1),
                found.group(2))));
        text = REFERENCE_LINK.matcher(text).replaceAll(found -> Matcher.quoteReplacement(markLink(found.group(1),
                references.getOrDefault((found.group(2).isEmpty() ? found.group(1) : found.group(2))
                        .toLowerCase(Locale.ROOT), ""))));
        text = HARD_BREAK.matcher(text).replaceAll("\n\n");
        text = ESCAPE.matcher(text).replaceAll("$1");
        text = TAG.matcher(text).replaceAll("");
        text = PICTURE_ROW.matcher(text).replaceAll(found -> Matcher.quoteReplacement("\n" + PICTURE
                + found.group().replaceAll("[ \t]*\u0002[ \t]*\n?[ \t]*\u0001", " ").replace("\u0001", "")
                .replace("\u0002", "") + "\n"));
        text = SPACING.matcher(text).replaceAll(" ");
        text = CODE_POINT.matcher(text).replaceAll(found -> Matcher.quoteReplacement(decode(found)));

        text = BLANK_LINE.matcher(text).replaceAll("");
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
    }

    private static String markPicture(String url) {
        return url.startsWith("https://") && !url.toLowerCase(Locale.ROOT).endsWith(".svg")
                ? PICTURE + url + PICTURE_END : "";
    }

    private static String markLink(String words, String url) {
        return url.startsWith("https://") || url.startsWith("http://")
                ? LINK_START + words + LINK_URL + url + LINK_END : words;
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
                this.endTable();
                this.addRoom(PARAGRAPH);
                code = !code;
                continue;
            }
            if (isTableRow(line)) {
                this.endParagraph();
                this.table.add(Stream.of(CELL_BAR.split(line.strip().replaceAll("^[|\u2502\u2503\u2551]|"
                        + "[|\u2502\u2503\u2551]$", ""), -1)).map(cell -> SPACES.matcher(MARKS.matcher(LINK_MARKS
                        .matcher(cell).replaceAll("")).replaceAll("")).replaceAll(" ").strip()).toList());
                continue;
            }
            boolean boxRule = BOX_RULE.matcher(line).matches()
                    && line.chars().anyMatch(c -> c >= 0x2500 && c <= 0x257f);
            if (!line.isBlank() && (boxRule || (!this.table.isEmpty() && TABLE_RULE.matcher(line).matches()))) {
                continue;
            }
            this.endTable();
            if (code) {
                this.add(LINK_MARKS.matcher(line).replaceAll(""), STEP, Palette.FAINT, List.of());
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
            if (line.isBlank() || EMPTY_TITLE.matcher(line).matches()) {
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
                this.wrap(bullet.group(2), findIndent(bullet.group(1)), "• ", this.findColor(bullet.group(2)));
            } else if (numbered.matches()) {
                this.endParagraph();
                this.wrap(numbered.group(3), findIndent(numbered.group(1)), numbered.group(2) + ". ",
                        this.findColor(numbered.group(3)));
            } else if (quote.matches()) {
                this.endParagraph();
                this.wrap(quote.group(1), STEP, "", Palette.DIM);
            } else {
                this.paragraph.append(this.paragraph.isEmpty() ? "" : " ").append(line.strip());
            }
        }
        this.endParagraph();
        this.endTable();
    }

    private static boolean isTableRow(String line) {
        String stripped = line.strip();
        return CELL_BAR.matcher(stripped).find() && !BOX_RULE.matcher(stripped).matches()
                && (stripped.startsWith("|") || stripped.chars().anyMatch(c -> c >= 0x2500 && c <= 0x257f));
    }

    private void endTable() {
        if (this.table.isEmpty()) {
            return;
        }
        int count = this.table.stream().mapToInt(List::size).max().orElse(0);
        int[] widths = new int[count];
        for (List<String> row : this.table) {
            for (int at = 0; at < row.size(); at++) {
                widths[at] = Math.max(widths[at], this.canvas.measureText(row.get(at)) + CELL_GAP);
            }
        }
        while (Arrays.stream(widths).sum() > this.width) {
            int widest = 0;
            for (int at = 1; at < count; at++) {
                widest = widths[at] > widths[widest] ? at : widest;
            }
            if (widths[widest] <= NARROWEST_CELL) {
                break;
            }
            widths[widest]--;
        }
        List<Integer> columns = new ArrayList<>();
        int x = 0;
        for (int width : widths) {
            columns.add(x);
            x += width;
        }
        this.addRoom(PARAGRAPH);
        for (int at = 0; at < this.table.size(); at++) {
            this.out.add(new Line(String.join("\t", this.table.get(at)), 0, at == 0 ? Palette.ON : Palette.TEXT,
                    this.out.isEmpty() ? 0 : this.above, "", List.copyOf(columns)));
            this.above = 0;
        }
        this.table.clear();
        this.addRoom(PARAGRAPH);
    }

    private void addTitle(String words, int level) {
        this.endParagraph();
        this.addRoom(HEADING);
        this.wrap(words, 0, "", level <= 2 ? Palette.ACCENT : Palette.ON);
        this.addRoom(3);
    }

    private void endParagraph() {
        if (!this.paragraph.isEmpty()) {
            this.wrap(this.paragraph.toString(), 0, "", this.findColor(this.paragraph.toString()));
            this.paragraph.setLength(0);
        }
    }

    private void wrap(String words, int indent, String prefix, int color) {
        List<Word> parts = readWords(words);
        if (parts.isEmpty()) {
            return;
        }
        int hang = this.canvas.measureText(prefix);
        int room = Math.max(40, this.width - indent - hang);
        StringBuilder line = new StringBuilder(prefix);
        List<Span> spans = new ArrayList<>();
        int start = prefix.length();
        boolean first = true;
        for (Word word : parts) {
            if (line.length() > start && this.canvas.measureText(line.substring(start) + " " + word.text()) > room) {
                this.add(line.toString(), first ? indent : indent + hang, color, spans);
                first = false;
                line.setLength(0);
                spans = new ArrayList<>();
                start = 0;
            }
            if (line.length() > start) {
                line.append(' ');
            }
            int from = line.length();
            line.append(word.text());
            if (!word.bold() && word.url().isEmpty()) {
                continue;
            }

            Span before = spans.isEmpty() ? null : spans.getLast();
            if (before != null && before.to() == from - 1 && before.bold() == word.bold()
                    && before.url().equals(word.url())) {
                spans.set(spans.size() - 1, new Span(before.from(), line.length(), word.bold(), word.url()));
            } else {
                spans.add(new Span(from, line.length(), word.bold(), word.url()));
            }
        }
        this.add(line.toString(), first ? indent : indent + hang, color, spans);
    }

    private static List<Word> readWords(String words) {
        List<String> urls = new ArrayList<>();
        Matcher link = LINK_ADDRESS.matcher(words);
        StringBuilder bare = new StringBuilder();
        while (link.find()) {
            urls.add(link.group(1));
            link.appendReplacement(bare, String.valueOf(LINK_URL));
        }
        link.appendTail(bare);
        String text = MARKS.matcher(BOLD_MARK.matcher(bare).replaceAll(String.valueOf(BOLD))).replaceAll("");
        List<Word> out = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean bold = false;
        boolean wordBold = false;
        String url = "";
        String wordUrl = "";
        int links = 0;
        for (int at = 0; at <= text.length(); at++) {
            char one = at < text.length() ? text.charAt(at) : ' ';
            if (one == BOLD) {
                bold = !bold;
            } else if (one == LINK_START) {
                url = links < urls.size() ? urls.get(links) : "";
            } else if (one == LINK_URL) {
                url = "";
                links++;
            } else if (Character.isWhitespace(one)) {
                if (!word.isEmpty()) {
                    out.add(new Word(word.toString(), wordBold, wordUrl));
                    word.setLength(0);
                }
            } else {
                if (word.isEmpty()) {
                    wordBold = bold;
                    wordUrl = url;
                }
                word.append(one);
            }
        }
        return out;
    }

    private void add(String text, int indent, int color, List<Span> spans) {
        this.out.add(new Line(text, indent, color, this.out.isEmpty() ? 0 : this.above, "", List.of(),
                List.copyOf(spans)));
        this.above = 0;
    }

    private void addRoom(int room) {
        this.above = Math.max(this.above, room);
    }

    private static int findIndent(String spaces) {
        return Math.min(3, spaces.length() / 2) * STEP;
    }
}
