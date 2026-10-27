package dev.moon.hotwire.report;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ReportBundle {
    static final int MAX_ZIPS = 10;
    private static final int MOST_TITLE = 48;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss",
            Locale.ROOT).withZone(ZoneId.systemDefault());

    private static final Pattern SECRET_FLAG = Pattern.compile(
            "(?i)(--(?:accessToken|session|xuid|clientId|uuid|username|userProperties))(\\s+|=)\\S+");
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)\\b(access_?token|refresh_?token|token|api_?key|secret|password|authorization|bearer)"
                    + "(\"?\\s*[:= ]\\s*\"?)(?:bearer\\s+)?[^\\s\",}]+");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]+\\.?[A-Za-z0-9_-]*");
    private static final Pattern UUID = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}\\b");
    private static final Pattern IP_WITH_PORT = Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}:\\d{1,5}\\b");

    private static final Pattern HOME = Pattern.compile("(?i)([a-z]:[\\\\/]+users[\\\\/]+|/home/|/Users/)"
            + "(?:[^\\\\/\\r\\n\"':]+?(?=[\\\\/])|[^\\\\/\\s\"':]+)");
    private static final Pattern USER_VARIABLE = Pattern.compile(
            "(?im)^(\\s*(?:USERNAME|USER|LOGNAME|HOME|USERPROFILE)\\s*=\\s*).+$");

    private static final Pattern CHAT = Pattern.compile("\\[CHAT\\](?! \\[Hotwire\\])");

    private static final Pattern ZIP = Pattern.compile("(?:Hotwire ([0-9]{4}-[0-9]{2}-[0-9]{2}"
            + " [0-9]{2}-[0-9]{2}-[0-9]{2})(?: .+)?|hotwire-(?:report|crash)-([0-9_-]+))\\.zip");

    private static final Pattern UNSAFE = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");

    private ReportBundle() {
    }

    public static Path write(Path folder, Instant at, String title, Map<String, String> texts, Set<String> personal)
            throws IOException {
        Files.createDirectories(folder);
        String words = UNSAFE.matcher(title).replaceAll("").strip();
        words = words.substring(0, Math.min(MOST_TITLE, words.length())).strip();
        Path zip = folder.resolve("Hotwire " + FILE_TIME.format(at) + (words.isEmpty() ? "" : " " + words) + ".zip");
        try (OutputStream file = Files.newOutputStream(zip); ZipOutputStream out = new ZipOutputStream(file)) {
            for (Map.Entry<String, String> text : texts.entrySet()) {
                out.putNextEntry(new ZipEntry(text.getKey()));
                out.write(redact(text.getValue(), personal).getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        prune(folder);
        return zip;
    }

    static String redact(String text, Set<String> personal) {
        List<String> retained = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (!CHAT.matcher(line).find()) {
                retained.add(line);
            }
        }
        String out = String.join("\n", retained);
        out = SECRET_FLAG.matcher(out).replaceAll("$1$2[removed]");
        out = JWT.matcher(out).replaceAll("[removed]");
        out = KEY_VALUE.matcher(out).replaceAll("$1$2[removed]");
        out = UUID.matcher(out).replaceAll("[id]");
        out = IP_WITH_PORT.matcher(out).replaceAll("[address]");
        out = HOME.matcher(out).replaceAll("$1[user]");
        out = USER_VARIABLE.matcher(out).replaceAll("$1[removed]");
        for (String word : personal) {
            if (word.length() >= 3) {
                out = Pattern.compile("(?i)\\b" + Pattern.quote(word) + "\\b").matcher(out)
                        .replaceAll(Matcher.quoteReplacement("[player]"));
            }
        }
        return out;
    }

    private static String readTime(Path zip) {
        Matcher name = ZIP.matcher(zip.getFileName().toString());
        if (!name.matches()) {
            return "";
        }
        String time = name.group(1) != null ? name.group(1) : name.group(2);
        return time.replaceAll("\\D", "");
    }

    private static void prune(Path folder) throws IOException {
        List<Path> zips;
        try (Stream<Path> files = Files.list(folder)) {
            zips = files.filter(file -> ZIP.matcher(file.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(ReportBundle::readTime).reversed()).toList();
        }
        for (Path old : zips.subList(Math.min(MAX_ZIPS, zips.size()), zips.size())) {
            Files.deleteIfExists(old);
        }
    }
}
