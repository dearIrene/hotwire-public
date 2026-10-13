package dev.moon.hotwire.report;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
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
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private static final Pattern SECRET_FLAG = Pattern.compile(
            "(?i)(--(?:accessToken|session|xuid|clientId|uuid|username|userProperties))(\\s+|=)\\S+");
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)\\b(access_?token|refresh_?token|token|api_?key|secret|password|authorization|bearer)"
                    + "(\"?\\s*[:= ]\\s*\"?)(?:bearer\\s+)?[^\\s\",}]+");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]+\\.?[A-Za-z0-9_-]*");
    private static final Pattern UUID = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}\\b");
    private static final Pattern IP_WITH_PORT = Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}:\\d{1,5}\\b");

    private static final Pattern CHAT = Pattern.compile("\\[CHAT\\](?! \\[Hotwire\\])");

    private ReportBundle() {
    }

    public static Path write(Path folder, Instant at, Map<String, String> texts, Set<String> personal)
            throws IOException {
        Files.createDirectories(folder);
        Path zip = folder.resolve("hotwire-report-" + FILE_TIME.format(at) + ".zip");
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
        for (String word : personal) {
            if (word.length() >= 3) {
                out = Pattern.compile("(?i)\\b" + Pattern.quote(word) + "\\b").matcher(out)
                        .replaceAll(Matcher.quoteReplacement("[player]"));
            }
        }
        return out;
    }

    private static void prune(Path folder) throws IOException {
        List<Path> zips;
        try (Stream<Path> files = Files.list(folder)) {
            zips = files.filter(file -> file.getFileName().toString().startsWith("hotwire-report-"))
                    .sorted(Comparator.comparing(Path::getFileName).reversed()).toList();
        }
        for (Path old : zips.subList(Math.min(MAX_ZIPS, zips.size()), zips.size())) {
            Files.deleteIfExists(old);
        }
    }
}
